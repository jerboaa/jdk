/*
 * Copyright (c) 2023, Red Hat, Inc.
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

package jdk.tools.jlink.internal.plugins;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jdk.tools.jlink.internal.JRTArchive;
import jdk.tools.jlink.internal.JlinkCLIArgsListener;
import jdk.tools.jlink.internal.Platform;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolEntry;
import jdk.tools.jlink.plugin.ResourcePoolModule;

import static jdk.tools.jlink.internal.JlinkTask.RUNIMAGE_LINK_STAMP;
import static jdk.tools.jlink.internal.JlinkTask.CLI_RESOURCE_FILE;
import static jdk.tools.jlink.internal.JlinkTask.RESPATH_PATTERN;


/**
 * Plugin to collect resources from jmod which aren't classes or
 * resources. Needed for the the run-time image based jlink.
 */
public final class JlinkResourcesListPlugin extends AbstractPlugin implements JlinkCLIArgsListener {
    private static final String NAME = "add-run-image-resources";
    private static final String JLINK_MOD_NAME = "jdk.jlink";
    // This resource is being used in JLinkTask which passes its contents to
    // RunImageArchive for further processing.
    private static final String RESPATH = "/" + JLINK_MOD_NAME + "/" + RESPATH_PATTERN;
    private static final String CLI_RESOURCE = "/" + JLINK_MOD_NAME + "/" + CLI_RESOURCE_FILE;
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile(".*\\s.*");
    private static final byte[] EMPTY_RESOURCE_BYTES = new byte[] {};

    // Type file format:
    // '<type>|{0,1}|<sha-sum>|<file-path>'
    //   (1)    (2)      (3)      (4)
    //
    // Where fields are:
    //
    // (1) The resource type as specified by ResourcePoolEntry.type()
    // (2) Symlink designator. 0 => regular resource, 1 => symlinked resource
    // (3) The SHA-512 sum of the resources' content. The link to the target
    //     for symlinked resources.
    // (4) The relative file path of the resource
    private static final String TYPE_FILE_FORMAT = "%d|%d|%s|%s";

    private final Map<String, List<String>> nonClassResEntries;

    private List<String> commands;

    public JlinkResourcesListPlugin() {
        super(NAME);
        this.nonClassResEntries = new ConcurrentHashMap<>();
    }

    @Override
    public boolean isHidden() {
        return true; // Don't show in --list-plugins output
    }

    @Override
    public ResourcePool transform(ResourcePool in, ResourcePoolBuilder out) {
        // Only add resources if we have the jdk.jlink module part of the
        // link.
        Optional<ResourcePoolModule> jdkJlink = in.moduleView().findModule(JLINK_MOD_NAME);
        if (jdkJlink.isPresent()) {
            Platform targetPlatform = getTargetPlatform(in);
            in.transformAndCopy(e -> recordAndFilterEntry(e, targetPlatform), out);
            addModuleResourceEntries(in, out);
            addCLIResource(out);
        } else {
            in.transformAndCopy(Function.identity(), out);
        }
        return out.build();
    }

    private void addCLIResource(ResourcePoolBuilder out) {
        out.add(ResourcePoolEntry.create(CLI_RESOURCE, getCliBytes()));
    }

    private byte[] getCliBytes() {
        StringBuilder builder = new StringBuilder();
        for (String s: commands) {
            Matcher m = WHITESPACE_PATTERN.matcher(s);
            if (m.matches()) {
                // Quote arguments containing whitespace
                builder.append("\"");
                builder.append(s);
                builder.append("\"");
            } else {
                builder.append(s);
            }
            builder.append(" ");
        }
        builder.append("\n");
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    // Filter the resource we add.
    @Override
    public List<String> getExcludePatterns() {
        return List.of("glob:" + CLI_RESOURCE,
                       "regex:/jdk\\.jlink/" + String.format(RESPATH_PATTERN, ".*"));
    }

    private Platform getTargetPlatform(ResourcePool in) {
        String platform = in.moduleView().findModule("java.base")
                .map(ResourcePoolModule::targetPlatform)
                .orElseThrow(() -> new AssertionError("java.base not found"));
        return Platform.parsePlatform(platform);
    }

    private void addModuleResourceEntries(ResourcePool in, ResourcePoolBuilder out) {
        Set<String> inputModules = in.moduleView().modules()
                                                  .map(rm -> rm.name())
                                                  .collect(Collectors.toSet());
        inputModules.stream().sorted().forEach(module -> {
            String mResource = String.format(RESPATH, module);
            List<String> mResources = nonClassResEntries.get(module);
            if (mResources == null) {
                // We create empty resource files for modules in the resource
                // pool view, but which don't themselves contain native resources
                // or config files.
                out.add(ResourcePoolEntry.create(mResource, EMPTY_RESOURCE_BYTES));
            } else {
                String mResContent = mResources.stream().sorted()
                                               .collect(Collectors.joining("\n"));
                out.add(ResourcePoolEntry.create(mResource, mResContent.getBytes(StandardCharsets.UTF_8)));
            }
        });
    }

    private ResourcePoolEntry recordAndFilterEntry(ResourcePoolEntry entry, Platform platform) {
        // Note that the jmod_resources file is a resource file, so we cannot
        // add ourselves due to this condition. However, we want to not add
        // an old version of the resource file again.
        if (entry.type() != ResourcePoolEntry.Type.CLASS_OR_RESOURCE) {
            if (entry.type() == ResourcePoolEntry.Type.TOP) {
                return entry; // Handled by ReleaseInfoPlugin, nothing to do
            }
            List<String> moduleResources = nonClassResEntries.computeIfAbsent(entry.moduleName(), a -> new ArrayList<>());

            JRTArchive.ResourceFileEntry rfEntry = JRTArchive.ResourceFileEntry.toResourceFileEntry(entry, platform);
            moduleResources.add(rfEntry.encodeToString());
        }
        return entry;
    }

    @Override
    public Set<State> getState() {
        return EnumSet.of(State.AUTO_ENABLED, State.FUNCTIONAL);
    }

    @Override
    public boolean hasArguments() {
        return false;
    }

    @Override
    public Category getType() {
        // Ensure we run in a later stage as we need to generate
        // SHA-512 sums for non-(class/resource) files. The jmod_resources
        // files can be considered meta-info describing the universe we
        // draft from.
        return Category.METAINFO_ADDER;
    }

    @Override
    public void process(List<String> commands) {
        this.commands = commands;
    }
}
