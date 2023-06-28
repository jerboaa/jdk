/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import tests.Helper;
import tests.JImageGenerator;
import tests.JImageGenerator.JLinkTask;
import tests.JImageHelper;
import tests.JImageValidator;

/*
 * @test
 * @summary Test image creation without jmods being present
 * @requires (vm.compMode != "Xcomp" & os.maxMemory >= 2g)
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.plugin
 *          jdk.jlink/jdk.tools.jimage
 *          jdk.compiler
 * @build tests.*
 * @run main/othervm/timeout=600 -Xmx1g JLinkTestJmodsLess
 */
public class JLinkTestJmodsLess {

    private static final boolean DEBUG = true;

    public static void main(String[] args) throws Exception {

        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }

        testBasicJlinking(helper);
        testCustomModuleJlinking(helper);
        testJlinkJavaSEReproducible(helper);
        testAddOptions(helper);
        testSaveJlinkOptions(helper);
        testJmodLessSystemModules(helper);
        testJmodLessJmodFullCompare(helper);
    }

    public static void testAddOptions(Helper helper) throws Exception {
        Path finalImage = createJavaImageJmodLess(new BaseJlinkSpecBuilder()
                                                        .addExtraOption("--add-options")
                                                        .addExtraOption("-Xlog:gc=info:stderr -XX:+UseParallelGC")
                                                        .name("java-base-with-opts")
                                                        .addModule("java.base")
                                                        .validatingModule("java.base")
                                                        .helper(helper)
                                                        .build());
        verifyListModules(finalImage, List.of("java.base"));
        verifyParallelGCInUse(finalImage);
        System.out.println("testAddOptions PASSED!");
    }

    public static void testSaveJlinkOptions(Helper helper) throws Exception {
        String vendorVersion = "jmodless";
        Path jlinkOptsFile = createJlinkOptsFile(List.of("--compress", "zip-6", "--vendor-version", vendorVersion));
        Path finalImage = createJavaImageJmodLess(new BaseJlinkSpecBuilder()
                                                        .addExtraOption("--save-jlink-argfiles")
                                                        .addExtraOption(jlinkOptsFile.toAbsolutePath().toString())
                                                        .addModule("jdk.jlink")
                                                        .name("java-base-with-jlink-opts")
                                                        .helper(helper)
                                                        .validatingModule("java.base")
                                                        .build());
        verifyVendorVersion(finalImage, vendorVersion);
        System.out.println("testSaveJlinkOptions PASSED!");
    }

    public static void testCustomModuleJlinking(Helper helper) throws Exception {
        String customModule = "leaf1";
        helper.generateDefaultJModule(customModule);

        // create a base image including jdk.jlink and the leaf1 module. This will
        // add the leaf1 module's module path.
        Path jlinkImage = createBaseJlinkImage(new BaseJlinkSpecBuilder()
                                                    .helper(helper)
                                                    .name("cmod-jlink")
                                                    .addModule(customModule)
                                                    .validatingModule("java.base") // not used
                                                    .build());

        // Now that the base image already includes the 'leaf1' module, it should
        // be possible to jlink it again, asking for *only* the 'leaf1' plugin even
        // though we won't have any jmods directories present.
        Path finalImage = jlinkUsingImage(new JlinkSpecBuilder()
                                                .imagePath(jlinkImage)
                                                .helper(helper)
                                                .name(customModule)
                                                .expectedLocation(String.format("/%s/%s/com/foo/bar/X.class", customModule, customModule))
                                                .addModule(customModule)
                                                .validatingModule(customModule)
                                                .build());
        // Expected only the transitive closure of "leaf1" module in the --list-modules
        // output of the java launcher.
        List<String> expectedModules = List.of("java.base", customModule);
        verifyListModules(finalImage, expectedModules);
        System.out.println("testCustomModuleJlinking PASSED!");
    }

    public static void testBasicJlinking(Helper helper) throws Exception {
        Path finalImage = createJavaBaseJmodLess(helper, "java-base");
        verifyListModules(finalImage, List.of("java.base"));
        System.out.println("testBasicJlinking PASSED!");
    }

    public static void testJmodLessJmodFullCompare(Helper helper) throws Exception {
        // create a java.se using jmod-less approach
        //Path javaSEJmodLess = createJavaImageJmodLess(helper, "java-se-jmodless", "java.se");
        Path javaSEJmodLess = createJavaImageJmodLess(new BaseJlinkSpecBuilder()
                                                            .helper(helper)
                                                            .name("java-se-jmodless")
                                                            .addModule("java.se")
                                                            .validatingModule("java.se")
                                                            .build());

        // create a java.se using packaged modules (jmod-full)
        Path javaSEJmodFull = JImageGenerator.getJLinkTask()
                .output(helper.createNewImageDir("java-se-jmodfull"))
                .addMods("java.se").call().assertSuccess();

        compareRecursively(javaSEJmodLess, javaSEJmodFull);
        System.out.println("testJmodLessJmodFullCompare PASSED!");
    }

    /*
     * SystemModule classes are module specific. If the jlink is based on the
     * modules image, then earlier generated SystemModule classes shall not get
     * propagated.
     */
    public static void testJmodLessSystemModules(Helper helper) throws Exception {
        if (isWindows()) {
            System.out.println("testJmodLessSystemModules() skipped on Windows.");
            return; // FIXME: Investigate why an ALL-MODULE-PATH jlink times out on Windows.
        }
        // create a full image with all modules using jmod-less approach
        Path allModsJmodLess = createJavaImageJmodLess(new BaseJlinkSpecBuilder()
                                                            .helper(helper)
                                                            .name("all-mods-jmodless")
                                                            .addModule("ALL-MODULE-PATH")
                                                            .validatingModule("java.base")
                                                            .build());
        // Derive another jmodless image, including java.se and jdk.jlink.
        Path javaseJmodless = jlinkUsingImage(new JlinkSpecBuilder()
                                                    .helper(helper)
                                                    .imagePath(allModsJmodLess)
                                                    .name("javase-jlink-jmodless-derived")
                                                    .addModule("java.se")
                                                    .addModule("jdk.jlink")
                                                    .validatingModule("java.base")
                                                    .build());
        // Finally attempt another jmodless link reducing java.se to java.base
        jlinkUsingImage(new JlinkSpecBuilder()
                                .helper(helper)
                                .imagePath(javaseJmodless)
                                .name("java.base-from-java.se-derived")
                                .addModule("java.base")
                                .expectedLocation("/java.base/jdk/internal/module/SystemModulesMap.class")
                                .expectedLocation("/java.base/jdk/internal/module/SystemModules.class")
                                .expectedLocation("/java.base/jdk/internal/module/SystemModules$all.class")
                                .unexpectedLocation("/java.base/jdk/internal/module/SystemModules$0.class")
                                .unexpectedLocation("/java.base/jdk/internal/module/SystemModules$default.class")
                                .validatingModule("java.base")
                                .build());
        System.out.println("testJmodLessSystemModules PASSED!");
    }

    // Visit all files in the given directories checking that they're byte-by-byte identical
    private static void compareRecursively(Path javaSEJmodLess,
            Path javaSEJmodFull) throws IOException, AssertionError {
        FilesCapturingVisitor jmodFullVisitor = new FilesCapturingVisitor(javaSEJmodFull);
        FilesCapturingVisitor jmodLessVisitor = new FilesCapturingVisitor(javaSEJmodLess);
        Files.walkFileTree(javaSEJmodFull, jmodFullVisitor);
        Files.walkFileTree(javaSEJmodLess, jmodLessVisitor);
        List<String> jmodFullFiles = jmodFullVisitor.filesVisited();
        List<String> jmodLessFiles = jmodLessVisitor.filesVisited();
        Collections.sort(jmodFullFiles);
        Collections.sort(jmodLessFiles);

        if (jmodFullFiles.size() != jmodLessFiles.size()) {
            throw new AssertionError(String.format("Size of files different for jmod-less (%d) vs jmod-full (%d) java.se jlink", jmodLessFiles.size(), jmodFullFiles.size()));
        }
        // Compare all files except the modules image
        for (int i = 0; i < jmodFullFiles.size(); i++) {
            String jmodFullPath = jmodFullFiles.get(i);
            String jmodLessPath = jmodLessFiles.get(i);
            if (!jmodFullPath.equals(jmodLessPath)) {
                throw new AssertionError(String.format("jmod-full path (%s) != jmod-less path (%s)", jmodFullPath, jmodLessPath));
            }
            if (jmodFullPath.equals("lib/modules")) {
                continue;
            }
            Path a = javaSEJmodFull.resolve(Path.of(jmodFullPath));
            Path b = javaSEJmodLess.resolve(Path.of(jmodLessPath));
            if (Files.mismatch(a, b) != -1L) {
                throw new AssertionError("Files mismatch: " + a + " vs. " + b);
            }
        }
        // Compare jimage contents by iterating its entries and comparing their
        // paths and content bytes
        //
        // Note: The files aren't byte-by-byte comparable (probably due to string hashing
        // and offset differences in container bytes)
        Path jimageJmodLess = javaSEJmodLess.resolve(Path.of("lib")).resolve(Path.of("modules"));
        Path jimageJmodFull = javaSEJmodFull.resolve(Path.of("lib")).resolve(Path.of("modules"));
        List<String> jimageContentJmodLess = JImageHelper.listContents(jimageJmodLess);
        List<String> jimageContentJmodFull = JImageHelper.listContents(jimageJmodFull);
        if (jimageContentJmodLess.size() != jimageContentJmodFull.size()) {
            throw new AssertionError(String.format("Size of jimage content differs for jmod-less (%d) v. jmod-full (%d)", jimageContentJmodLess.size(), jimageContentJmodFull.size()));
        }
        for (int i = 0; i < jimageContentJmodFull.size(); i++) {
            if (!jimageContentJmodFull.get(i).equals(jimageContentJmodLess.get(i))) {
                throw new AssertionError(String.format("Jimage content differs at index %d: jmod-full was: '%s' jmod-less was: '%s'",
                                                       i,
                                                       jimageContentJmodFull.get(i),
                                                       jimageContentJmodLess.get(i)
                                                       ));
            }
            String loc = jimageContentJmodFull.get(i);
            if (isTreeInfoResource(loc)) {
                // Skip container bytes as those are offsets to the content
                // which might be different between jlinks.
                continue;
            }
            byte[] resBytesFull = JImageHelper.getLocationBytes(loc, jimageJmodFull);
            byte[] resBytesLess = JImageHelper.getLocationBytes(loc, jimageJmodLess);
            if (resBytesFull.length != resBytesLess.length || Arrays.mismatch(resBytesFull, resBytesLess) != -1) {
                throw new AssertionError("Content bytes mismatch for " + loc);
            }
        }
    }

    private static boolean isTreeInfoResource(String path) {
        return path.startsWith("/packages") || path.startsWith("/modules");
    }

    public static void testJlinkJavaSEReproducible(Helper helper) throws Exception {
        String javaSeModule = "java.se";
        // create a java.se using jmod-less approach
        Path javaSEJmodLess1 = createJavaImageJmodLess(new BaseJlinkSpecBuilder()
                                                                   .helper(helper)
                                                                   .name("java-se-repro1")
                                                                   .addModule(javaSeModule)
                                                                   .validatingModule(javaSeModule)
                                                                   .build());

        // create another java.se version using jmod-less approach
        Path javaSEJmodLess2 = createJavaImageJmodLess(new BaseJlinkSpecBuilder()
                                                                   .helper(helper)
                                                                   .name("java-se-repro2")
                                                                   .addModule(javaSeModule)
                                                                   .validatingModule(javaSeModule)
                                                                   .build());
        if (Files.mismatch(javaSEJmodLess1.resolve("lib").resolve("modules"),
                           javaSEJmodLess2.resolve("lib").resolve("modules")) != -1L) {
            throw new RuntimeException("jlink producing inconsistent result for " + javaSeModule + " (jmod-less)");
        }
        System.out.println("testJlinkJavaSEReproducible PASSED!");
    }

    private static void verifyVendorVersion(Path finalImage, String vendorVersion) throws Exception {
        Process p = runJavaCmd(finalImage, List.of("--version"));
        BufferedReader buf = p.inputReader();
        List<String> outLines = new ArrayList<>();
        try (Stream<String> lines = buf.lines()) {
            if (!lines.anyMatch(l -> {
                    outLines.add(l);
                    return l.contains(vendorVersion); }
            )) {
                if (DEBUG) {
                    System.err.println(outLines.stream().collect(Collectors.joining("\n")));
                }
                throw new AssertionError("Expected vendor version " + vendorVersion + " in jlinked image.");
            }
        }
    }

    /**
     * Create a temporary file for use via --save-jlink-options-file
     * @param options The options to save in the file.
     * @return The path to the temporary file
     */
    private static Path createJlinkOptsFile(List<String> options) throws Exception {
        Path tmpFile = Files.createTempFile("JLinkTestJmodsLess", "jlink-options-file");
        tmpFile.toFile().deleteOnExit();
        String content = options.stream().collect(Collectors.joining("\n"));
        Files.writeString(tmpFile, content, StandardOpenOption.TRUNCATE_EXISTING);
        return tmpFile;
    }

    private static void verifyParallelGCInUse(Path finalImage) throws Exception {
        Process p = runJavaCmd(finalImage, List.of("--version"));
        BufferedReader buf = p.errorReader();
        try (Stream<String> lines = buf.lines()) {
            if (!lines.anyMatch(l -> l.endsWith("Using Parallel"))) {
                throw new AssertionError("Expected Parallel GC in place for jlinked image");
            }
        }
    }

    private static Path createJavaImageJmodLess(BaseJlinkSpec baseSpec) throws Exception {
        // create a base image only containing the jdk.jlink module and its transitive closure
        Path jlinkJmodlessImage = createBaseJlinkImage(baseSpec);

        // On Windows jvm.dll is in 'bin' after the jlink
        Path libjvm = Path.of((isWindows() ? "bin" : "lib"), "server", System.mapLibraryName("jvm"));
        JlinkSpecBuilder builder = new JlinkSpecBuilder();
        // And expect libjvm (not part of the jimage) to be present in the resulting image
        builder.expectedFile(libjvm.toString())
               .helper(baseSpec.getHelper())
               .name(baseSpec.getName())
               .validatingModule(baseSpec.getValidatingModule())
               .imagePath(jlinkJmodlessImage)
               .expectedLocation("/java.base/java/lang/String.class");
        for (String m: baseSpec.getModules()) {
            builder.addModule(m);
        }
        return jlinkUsingImage(builder.build());
    }

    private static Path createJavaBaseJmodLess(Helper helper, String name) throws Exception {
        BaseJlinkSpecBuilder builder = new BaseJlinkSpecBuilder();
        builder.helper(helper)
               .name(name)
               .addModule("java.base")
               .validatingModule("java.base");
        return createJavaImageJmodLess(builder.build());
    }

    /**
     * Ensure 'java --list-modules' lists the correct set of modules in the given
     * image.
     *
     * @param jlinkImage
     * @param expectedModules
     */
    private static void verifyListModules(Path image,
            List<String> expectedModules) throws Exception {
        Process javaListMods = runJavaCmd(image, List.of("--list-modules"));
        BufferedReader outReader = javaListMods.inputReader();
        List<String> actual = parseListMods(outReader);
        Collections.sort(actual);
        if (!expectedModules.equals(actual)) {
            throw new AssertionError("Different modules! Expected " + expectedModules + " got: " + actual);
        }
    }

    private static Process runJavaCmd(Path image, List<String> options) throws Exception {
        Path targetJava = image.resolve("bin").resolve(getJava());
        List<String> cmd = new ArrayList<>();
        cmd.add(targetJava.toString());
        for (String opt: options) {
            cmd.add(opt);
        }
        List<String> javaCmd = Collections.unmodifiableList(cmd);
        ProcessBuilder builder = new ProcessBuilder(javaCmd);
        Process p = builder.start();
        int status = p.waitFor();
        if (status != 0) {
            if (DEBUG) {
                BufferedReader errReader = p.errorReader();
                BufferedReader outReader = p.inputReader();
                readAndPrintReaders(errReader, outReader);
            }
            throw new AssertionError("'" + javaCmd.stream().collect(Collectors.joining(" ")) + "'"
                    + " expected to succeed!");
        }
        return p;
    }

    private static List<String> parseListMods(BufferedReader outReader) throws Exception {
        try (outReader) {
            return outReader.lines()
                    .map(a -> { return a.split("@", 2)[0];})
                    .filter(a -> !a.isBlank())
                    .collect(Collectors.toList());
        }
    }

    private static Path createBaseJlinkImage(BaseJlinkSpec baseSpec) throws Exception {
        // Jlink an image including jdk.jlink (i.e. the jlink tool). The
        // result must not contain a jmods directory.
        Path jlinkJmodlessImage = baseSpec.getHelper().createNewImageDir(baseSpec.getName() + "-jlink");
        JLinkTask task = JImageGenerator.getJLinkTask();
        if (baseSpec.getModules().contains("leaf1")) {
            task.modulePath(baseSpec.getHelper().getJmodDir().toString());
        }
        task.output(jlinkJmodlessImage);
        for (String module: baseSpec.getModules()) {
            task.addMods(module);
        }
        if (!baseSpec.getModules().contains("ALL-MODULE-PATH")) {
            task.addMods("jdk.jlink"); // needed for the recursive jlink
        }
        for (String opt: baseSpec.getExtraOptions()) {
            task.option(opt);
        }
        task.option("--verbose")
            .call().assertSuccess();
        // Verify the base image is actually jmod-less
        if (Files.exists(jlinkJmodlessImage.resolve("jmods"))) {
            throw new AssertionError("Must not contain 'jmods' directory");
        }
        return jlinkJmodlessImage;
    }

    private static Path jlinkUsingImage(JlinkSpec spec) throws Exception {
        String jmodLessGeneratedImage = "target-jmodless-" + spec.getName();
        Path targetImageDir = spec.getHelper().createNewImageDir(jmodLessGeneratedImage);
        Path targetJlink = spec.getImageToUse().resolve("bin").resolve(getJlink());
        String[] jlinkCmdArray = new String[] {
                targetJlink.toString(),
                "--output", targetImageDir.toString(),
                "--verbose",
                "--add-modules", spec.getModules().stream().collect(Collectors.joining(","))
        };
        List<String> jlinkCmd = new ArrayList<>();
        jlinkCmd.addAll(Arrays.asList(jlinkCmdArray));
        if (spec.getExtraJlinkOpts() != null && !spec.getExtraJlinkOpts().isEmpty()) {
            jlinkCmd.addAll(spec.getExtraJlinkOpts());
        }
        jlinkCmd = Collections.unmodifiableList(jlinkCmd); // freeze
        System.out.println("DEBUG: jmod-less jlink command: " + jlinkCmd.stream().collect(
                                                    Collectors.joining(" ")));
        ProcessBuilder builder = new ProcessBuilder(jlinkCmd);
        Process p = builder.start();
        BufferedReader errReader = p.errorReader();
        BufferedReader outReader = p.inputReader();
        int status = p.waitFor();
        if (status != 0) {
            // modules we asked for should be available in the image we used for jlinking
            if (DEBUG) {
                readAndPrintReaders(errReader, outReader);
            }
            throw new AssertionError("Expected jlink to pass given a jmodless image");
        }

        // validate the resulting image; Includes running 'java -version'
        JImageValidator validator = new JImageValidator(spec.getValidatingModule(), spec.getExpectedLocations(),
                targetImageDir.toFile(), spec.getUnexpectedLocations(), Collections.emptyList(), spec.getExpectedFiles());
        validator.validate(); // This doesn't validate locations
        if (!spec.getExpectedLocations().isEmpty() || !spec.getUnexpectedLocations().isEmpty()) {
            JImageValidator.validate(targetImageDir.resolve("lib").resolve("modules"), spec.getExpectedLocations(), spec.getUnexpectedLocations());
        }
        return targetImageDir;
    }

    private static void readAndPrintReaders(BufferedReader errReader,
            BufferedReader outReader) {
        System.err.println("Process error output:");
        errReader.lines().forEach(System.err::println);
        System.out.println("Process standard output:");
        outReader.lines().forEach(System.out::println);
    }

    private static String getJlink() {
        return getBinary("jlink");
    }

    private static String getJava() {
        return getBinary("java");
    }

    private static String getBinary(String binary) {
        return isWindows() ? binary + ".exe" : binary;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    static class BaseJlinkSpec {
        final Helper helper;
        final String name;
        final String validatingModule;
        final List<String> modules;
        final List<String> extraOptions;
        BaseJlinkSpec(Helper helper, String name, String validatingModule, List<String> modules, List<String> extraOptions) {
            this.helper = helper;
            this.name = name;
            this.modules = modules;
            this.extraOptions = extraOptions;
            this.validatingModule = validatingModule;
        }
        public String getValidatingModule() {
            return validatingModule;
        }
        public Helper getHelper() {
            return helper;
        }
        public String getName() {
            return name;
        }
        public List<String> getModules() {
            return modules;
        }
        public List<String> getExtraOptions() {
            return extraOptions;
        }
    }

    static class BaseJlinkSpecBuilder {
        Helper helper;
        String name;
        String validatingModule;
        List<String> modules = new ArrayList<>();
        List<String> extraOptions = new ArrayList<>();

        BaseJlinkSpecBuilder addModule(String module) {
            modules.add(module);
            return this;
        }

        BaseJlinkSpecBuilder addExtraOption(String option) {
            extraOptions.add(option);
            return this;
        }

        BaseJlinkSpecBuilder helper(Helper helper) {
            this.helper = helper;
            return this;
        }

        BaseJlinkSpecBuilder name(String name) {
            this.name = name;
            return this;
        }

        BaseJlinkSpecBuilder validatingModule(String module) {
            this.validatingModule = module;
            return this;
        }

        BaseJlinkSpec build() {
            if (name == null) {
                throw new IllegalStateException("Name must be set");
            }
            if (helper == null) {
                throw new IllegalStateException("helper must be set");
            }
            if (validatingModule == null) {
                throw new IllegalStateException("the module which should get validated must be set");
            }
            return new BaseJlinkSpec(helper, name, validatingModule, modules, extraOptions);
        }
    }

    static class JlinkSpec {
        final Path imageToUse;
        final Helper helper;
        final String name;
        final List<String> modules;
        final String validatingModule;
        final List<String> expectedLocations;
        final List<String> unexpectedLocations;
        final String[] expectedFiles;
        final List<String> extraJlinkOpts;
        JlinkSpec(Path imageToUse,
                  Helper helper,
                  String name,
                  List<String> modules,
                  String validatingModule,
                  List<String> expectedLocations,
                  List<String> unexpectedLocations,
                  String[] expectedFiles,
                  List<String> extraJlinkOpts) {
            this.imageToUse = imageToUse;
            this.helper = helper;
            this.name = name;
            this.modules = modules;
            this.validatingModule = validatingModule;
            this.expectedLocations = expectedLocations;
            this.unexpectedLocations = unexpectedLocations;
            this.expectedFiles = expectedFiles;
            this.extraJlinkOpts = extraJlinkOpts;
        }
        public Path getImageToUse() {
            return imageToUse;
        }
        public Helper getHelper() {
            return helper;
        }
        public String getName() {
            return name;
        }
        public List<String> getModules() {
            return modules;
        }
        public String getValidatingModule() {
            return validatingModule;
        }
        public List<String> getExpectedLocations() {
            return expectedLocations;
        }
        public List<String> getUnexpectedLocations() {
            return unexpectedLocations;
        }
        public String[] getExpectedFiles() {
            return expectedFiles;
        }
        public List<String> getExtraJlinkOpts() {
            return extraJlinkOpts;
        }
    }

    static class JlinkSpecBuilder {
        Path imageToUse;
        Helper helper;
        String name;
        List<String> modules = new ArrayList<>();
        String validatingModule;
        List<String> expectedLocations = new ArrayList<>();
        List<String> unexpectedLocations = new ArrayList<>();
        List<String> expectedFiles = new ArrayList<>();
        List<String> extraJlinkOpts = new ArrayList<>();

        JlinkSpec build() {
            if (imageToUse == null) {
                throw new IllegalStateException("No image to use for jlink specified!");
            }
            if (helper == null) {
                throw new IllegalStateException("No helper specified!");
            }
            if (name == null) {
                throw new IllegalStateException("No name for the image location specified!");
            }
            if (validatingModule == null) {
                throw new IllegalStateException("No module specified for after generation validation!");
            }
            return new JlinkSpec(imageToUse, helper, name, modules, validatingModule, expectedLocations, unexpectedLocations, expectedFiles.toArray(new String[0]), extraJlinkOpts);
        }

        JlinkSpecBuilder imagePath(Path image) {
            this.imageToUse = image;
            return this;
        }

        JlinkSpecBuilder helper(Helper helper) {
            this.helper = helper;
            return this;
        }

        JlinkSpecBuilder name(String name) {
            this.name = name;
            return this;
        }

        JlinkSpecBuilder addModule(String module) {
            modules.add(module);
            return this;
        }

        JlinkSpecBuilder validatingModule(String module) {
            this.validatingModule = module;
            return this;
        }

        JlinkSpecBuilder expectedLocation(String location) {
            expectedLocations.add(location);
            return this;
        }

        JlinkSpecBuilder unexpectedLocation(String location) {
            unexpectedLocations.add(location);
            return this;
        }

        JlinkSpecBuilder expectedFile(String file) {
            expectedFiles.add(file);
            return this;
        }

        JlinkSpecBuilder extraJlinkOpt(String opt) {
            extraJlinkOpts.add(opt);
            return this;
        }
    }

    static class FilesCapturingVisitor extends SimpleFileVisitor<Path> {
        private final Path basePath;
        private final List<String> filePaths = new ArrayList<>();
        public FilesCapturingVisitor(Path basePath) {
            this.basePath = basePath;
        }

        @Override
        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
            Path relative = basePath.relativize(path);
            filePaths.add(relative.toString());
            return FileVisitResult.CONTINUE;
        }

        List<String> filesVisited() {
            return filePaths;
        }
    }
}
