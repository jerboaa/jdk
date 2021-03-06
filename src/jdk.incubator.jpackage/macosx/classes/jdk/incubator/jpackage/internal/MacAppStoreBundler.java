/*
 * Copyright (c) 2014, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.incubator.jpackage.internal;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

import static jdk.incubator.jpackage.internal.StandardBundlerParam.*;
import static jdk.incubator.jpackage.internal.MacAppBundler.*;
import static jdk.incubator.jpackage.internal.OverridableResource.createResource;

public class MacAppStoreBundler extends MacBaseInstallerBundler {

    private static final ResourceBundle I18N = ResourceBundle.getBundle(
            "jdk.incubator.jpackage.internal.resources.MacResources");

    private static final String TEMPLATE_BUNDLE_ICON_HIDPI = "java.icns";
    private final static String DEFAULT_ENTITLEMENTS =
            "MacAppStore.entitlements";
    private final static String DEFAULT_INHERIT_ENTITLEMENTS =
            "MacAppStore_Inherit.entitlements";

    public static final BundlerParamInfo<String> MAC_APP_STORE_APP_SIGNING_KEY =
            new StandardBundlerParam<>(
            "mac.signing-key-app",
            String.class,
            params -> {
                String result = MacBaseInstallerBundler.findKey(
                        "3rd Party Mac Developer Application: " +
                        SIGNING_KEY_USER.fetchFrom(params),
                        SIGNING_KEYCHAIN.fetchFrom(params),
                        VERBOSE.fetchFrom(params));
                if (result != null) {
                    MacCertificate certificate = new MacCertificate(result);

                    if (!certificate.isValid()) {
                        Log.error(MessageFormat.format(
                                I18N.getString("error.certificate.expired"),
                                result));
                    }
                }

                return result;
            },
            (s, p) -> s);

    public static final BundlerParamInfo<String> MAC_APP_STORE_PKG_SIGNING_KEY =
            new StandardBundlerParam<>(
            "mac.signing-key-pkg",
            String.class,
            params -> {
                String result = MacBaseInstallerBundler.findKey(
                        "3rd Party Mac Developer Installer: " +
                        SIGNING_KEY_USER.fetchFrom(params),
                        SIGNING_KEYCHAIN.fetchFrom(params),
                        VERBOSE.fetchFrom(params));

                if (result != null) {
                    MacCertificate certificate = new MacCertificate(result);

                    if (!certificate.isValid()) {
                        Log.error(MessageFormat.format(
                                I18N.getString("error.certificate.expired"),
                                result));
                    }
                }

                return result;
            },
            (s, p) -> s);

    public static final StandardBundlerParam<File> MAC_APP_STORE_ENTITLEMENTS  =
            new StandardBundlerParam<>(
            Arguments.CLIOptions.MAC_APP_STORE_ENTITLEMENTS.getId(),
            File.class,
            params -> null,
            (s, p) -> new File(s));

    public static final BundlerParamInfo<String> INSTALLER_SUFFIX =
            new StandardBundlerParam<> (
            "mac.app-store.installerName.suffix",
            String.class,
            params -> "-MacAppStore",
            (s, p) -> s);

    public File bundle(Map<String, ? super Object> params,
            File outdir) throws PackagerException {
        Log.verbose(MessageFormat.format(I18N.getString(
                "message.building-bundle"), APP_NAME.fetchFrom(params)));

        IOUtils.writableOutputDir(outdir.toPath());

        // first, load in some overrides
        // icns needs @2 versions, so load in the @2 default
        params.put(DEFAULT_ICNS_ICON.getID(), TEMPLATE_BUNDLE_ICON_HIDPI);

        // now we create the app
        File appImageDir = APP_IMAGE_TEMP_ROOT.fetchFrom(params);
        try {
            appImageDir.mkdirs();

            try {
                MacAppImageBuilder.addNewKeychain(params);
            } catch (InterruptedException e) {
                Log.error(e.getMessage());
            }
            // first, make sure we don't use the local signing key
            params.put(DEVELOPER_ID_APP_SIGNING_KEY.getID(), null);
            File appLocation = prepareAppBundle(params);

            prepareEntitlements(params);

            String signingIdentity =
                    MAC_APP_STORE_APP_SIGNING_KEY.fetchFrom(params);
            String identifierPrefix =
                    BUNDLE_ID_SIGNING_PREFIX.fetchFrom(params);
            String entitlementsFile =
                    getConfig_Entitlements(params).toString();
            String inheritEntitlements =
                    getConfig_Inherit_Entitlements(params).toString();

            MacAppImageBuilder.signAppBundle(params, appLocation.toPath(),
                    signingIdentity, identifierPrefix,
                    entitlementsFile, inheritEntitlements);
            MacAppImageBuilder.restoreKeychainList(params);

            ProcessBuilder pb;

            // create the final pkg file
            File finalPKG = new File(outdir, INSTALLER_NAME.fetchFrom(params)
                    + INSTALLER_SUFFIX.fetchFrom(params)
                    + ".pkg");
            outdir.mkdirs();

            String installIdentify =
                    MAC_APP_STORE_PKG_SIGNING_KEY.fetchFrom(params);

            List<String> buildOptions = new ArrayList<>();
            buildOptions.add("productbuild");
            buildOptions.add("--component");
            buildOptions.add(appLocation.toString());
            buildOptions.add("/Applications");
            buildOptions.add("--sign");
            buildOptions.add(installIdentify);
            buildOptions.add("--product");
            buildOptions.add(appLocation + "/Contents/Info.plist");
            String keychainName = SIGNING_KEYCHAIN.fetchFrom(params);
            if (keychainName != null && !keychainName.isEmpty()) {
                buildOptions.add("--keychain");
                buildOptions.add(keychainName);
            }
            buildOptions.add(finalPKG.getAbsolutePath());

            pb = new ProcessBuilder(buildOptions);

            IOUtils.exec(pb);
            return finalPKG;
        } catch (PackagerException pe) {
            throw pe;
        } catch (Exception ex) {
            Log.verbose(ex);
            throw new PackagerException(ex);
        }
    }

    private File getConfig_Entitlements(Map<String, ? super Object> params) {
        return new File(CONFIG_ROOT.fetchFrom(params),
                APP_NAME.fetchFrom(params) + ".entitlements");
    }

    private File getConfig_Inherit_Entitlements(
            Map<String, ? super Object> params) {
        return new File(CONFIG_ROOT.fetchFrom(params),
                APP_NAME.fetchFrom(params) + "_Inherit.entitlements");
    }

    private void prepareEntitlements(Map<String, ? super Object> params)
            throws IOException {
        createResource(DEFAULT_ENTITLEMENTS, params)
                .setCategory(
                        I18N.getString("resource.mac-app-store-entitlements"))
                .setExternal(MAC_APP_STORE_ENTITLEMENTS.fetchFrom(params))
                .saveToFile(getConfig_Entitlements(params));

        createResource(DEFAULT_INHERIT_ENTITLEMENTS, params)
                .setCategory(I18N.getString(
                        "resource.mac-app-store-inherit-entitlements"))
                .saveToFile(getConfig_Entitlements(params));
    }

    ///////////////////////////////////////////////////////////////////////
    // Implement Bundler
    ///////////////////////////////////////////////////////////////////////

    @Override
    public String getName() {
        return I18N.getString("store.bundler.name");
    }

    @Override
    public String getID() {
        return "mac.appStore";
    }

    @Override
    public boolean validate(Map<String, ? super Object> params)
            throws ConfigException {
        try {
            Objects.requireNonNull(params);

            // hdiutil is always available so there's no need to test for
            // availability.
            // run basic validation to ensure requirements are met

            // we are not interested in return code, only possible exception
            validateAppImageAndBundeler(params);

            // reject explicitly set to not sign
            if (!Optional.ofNullable(MacAppImageBuilder.
                    SIGN_BUNDLE.fetchFrom(params)).orElse(Boolean.TRUE)) {
                throw new ConfigException(
                        I18N.getString("error.must-sign-app-store"),
                        I18N.getString("error.must-sign-app-store.advice"));
            }

            // make sure we have settings for signatures
            if (MAC_APP_STORE_APP_SIGNING_KEY.fetchFrom(params) == null) {
                throw new ConfigException(
                        I18N.getString("error.no-app-signing-key"),
                        I18N.getString("error.no-app-signing-key.advice"));
            }
            if (MAC_APP_STORE_PKG_SIGNING_KEY.fetchFrom(params) == null) {
                throw new ConfigException(
                        I18N.getString("error.no-pkg-signing-key"),
                        I18N.getString("error.no-pkg-signing-key.advice"));
            }

            // things we could check...
            // check the icons, make sure it has hidpi icons
            // check the category,
            // make sure it fits in the list apple has provided
            // validate bundle identifier is reverse dns
            // check for \a+\.\a+\..

            return true;
        } catch (RuntimeException re) {
            if (re.getCause() instanceof ConfigException) {
                throw (ConfigException) re.getCause();
            } else {
                throw new ConfigException(re);
            }
        }
    }

    @Override
    public File execute(Map<String, ? super Object> params,
            File outputParentDir) throws PackagerException {
        return bundle(params, outputParentDir);
    }

    @Override
    public boolean supported(boolean runtimeInstaller) {
        // return (!runtimeInstaller &&
        //         Platform.getPlatform() == Platform.MAC);
        return false; // mac-app-store not yet supported
    }

    @Override
    public boolean isDefault() {
        return false;
    }

}
