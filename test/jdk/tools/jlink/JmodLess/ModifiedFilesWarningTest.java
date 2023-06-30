/*
 * Copyright (c) 2023, Red Hat, Inc.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import tests.Helper;

/*
 * @test
 * @summary Verify warnings are being produced when jlinking in jmod-less mode and files have been changed
 * @requires (vm.compMode != "Xcomp" & os.maxMemory >= 2g)
 * @library ../../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.plugin
 *          jdk.jlink/jdk.tools.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 * @build tests.*
 * @run main/othervm -Xmx1g ModifiedFilesWarningTest
 */
public class ModifiedFilesWarningTest extends AbstractJmodLessTest {

    public static void main(String[] args) throws Exception {
        ModifiedFilesWarningTest test = new ModifiedFilesWarningTest();
        test.run();
    }

    @Override
    void runTest(Helper helper) throws Exception {
        Path initialImage = createJavaImageJmodLess(new BaseJlinkSpecBuilder()
                .name("java-base-jlink-with-mod")
                .addModule("java.base")
                .addModule("jdk.jlink")
                .validatingModule("java.base")
                .helper(helper)
                .build());

        // modify net.properties config file
        Path netPropertiesFile = initialImage.resolve("conf").resolve("net.properties");
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(netPropertiesFile)) {
            props.load(is);
        }
        String prevVal = (String)props.put("java.net.useSystemProxies", Boolean.TRUE.toString());
        if (prevVal == null || Boolean.getBoolean(prevVal) != false) {
            throw new AssertionError("Expected previous value to be false!");
        }
        try (OutputStream out = Files.newOutputStream(netPropertiesFile)) {
            props.store(out, "Modified net.properties file!");
        }

        CapturingHandler handler = new CapturingHandler();
        jlinkUsingImage(new JlinkSpecBuilder()
                                .helper(helper)
                                .imagePath(initialImage)
                                .name("java-base-jlink-with-mod-target")
                                .addModule("java.base")
                                .validatingModule("java.base")
                                .build(), handler);
        // verify we get the warning message
        expectMatch(netPropertiesFile.toString(), handler.stdErr());
        expectMatch("WARNING: ", handler.stdErr());
        expectMatch("has been modified", handler.stdErr());
    }

    private static void expectMatch(String string, List<String> lines) {
        boolean foundMatch = false;
        for (String line: lines) {
            if (line.contains(string)) {
                foundMatch = true;
                break;
            }
        }
        if (!foundMatch) {
            String content = lines.stream().collect(Collectors.joining(System.lineSeparator()));
            throw new AssertionError(String.format("Expected to find '%s' in '%s'", string, content));
        }
    }

    static class CapturingHandler extends StdErrOutHandler {

        private final List<String> stdout = new ArrayList<>();
        private final List<String> stderr = new ArrayList<>();

        @Override
        public void handleDefault(BufferedReader stdout, BufferedReader stderr) {
            try (stdout; stderr) {
                String line = null;
                while ((line = stdout.readLine()) != null) {
                    this.stdout.add(line);
                }
                line = null;
                while ((line = stderr.readLine()) != null) {
                    this.stderr.add(line + "\n");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public List<String> stdOut() {
            return stdout;
        }

        public List<String> stdErr() {
            return stderr;
        }
    }
}
