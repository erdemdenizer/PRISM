import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the Windows application image and its ZIP (ADR-0017). Run by the Maven profile
 * {@code package-image}; JDK only, source-launched, so nothing of it reaches the product.
 *
 * <p>It stops with a non-zero exit at the first problem: an image that fails a check is not
 * delivered. The checks here are static; what the launcher really does is proved by
 * {@code verify-image.ps1}, and an image is accepted only when that passes.
 */
public final class PackageImage {

    /** Undocumented in jpackage; read by the launcher itself. ADR-0017 records why and what guards it. */
    private static final String NO_RESTART = "win.norestart=true";

    private static final Path SOURCE = Path.of("packaging", "windows");
    private static final Path TARGET = Path.of("target");
    private static final Path WORK = TARGET.resolve("package");

    private PackageImage() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: PackageImage <version>");
        }
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new IllegalStateException("The Windows image is built on Windows.");
        }
        String version = args[0];
        boolean anyJdk = Boolean.getBoolean("prism.package.unverifiedJdk");
        String jdk = checkJdk(anyJdk);

        delete(WORK);
        Path input = WORK.resolve("input");
        Files.createDirectories(input);
        String mainJar = "prism-" + version + ".jar";
        Files.copy(TARGET.resolve(mainJar), input.resolve(mainJar));
        copyDependencies(input);

        Path demoProperties = WORK.resolve("prism-demo.properties");
        Files.writeString(
                demoProperties,
                Files.readString(SOURCE.resolve("prism-demo.properties")).replace("PRISM_MAIN_JAR", mainJar));

        List<String> modules = readModules();
        Path images = WORK.resolve("image");
        run(
                "jpackage",
                "--type",
                "app-image",
                "--name",
                "PRISM",
                "--app-version",
                version,
                "--vendor",
                "Prism",
                "--input",
                input.toString(),
                "--main-jar",
                mainJar,
                "--main-class",
                "prism.srm.app.Launcher",
                "--java-options",
                "--enable-native-access=ALL-UNNAMED",
                "--add-modules",
                String.join(",", modules),
                "--add-launcher",
                "prism-demo=" + demoProperties,
                "--dest",
                images.toString(),
                "--temp",
                WORK.resolve("temp").toString());

        Path image = images.resolve("PRISM");
        // jpackage leaves the launchers read-only, and on Windows that stops ./mvnw clean from deleting them.
        for (String launcher : List.of("PRISM.exe", "prism-demo.exe")) {
            if (!image.resolve(launcher).toFile().setWritable(true)) {
                throw new IOException("Cannot make writable: " + launcher);
            }
        }
        Files.copy(SOURCE.resolve("README.txt"), image.resolve("README.txt"));
        addNoRestart(image.resolve("app").resolve("PRISM.cfg"));
        addNoRestart(image.resolve("app").resolve("prism-demo.cfg"));
        check(image, modules);

        Path zip = WORK.resolve("prism-" + version + "-windows-x64.zip");
        zip(image, zip);
        String sha = sha256(zip);
        Files.writeString(
                WORK.resolve(zip.getFileName() + ".sha256"),
                sha + "  " + zip.getFileName() + "\n",
                StandardCharsets.US_ASCII);

        probeJar();
        if (anyJdk) {
            Files.writeString(
                    WORK.resolve("JDK_NOT_VERIFIED.txt"),
                    "Built with " + jdk + ", which verified-jdk.txt does not name. Not deliverable.\n");
        }
        System.out.println("Image: " + image);
        System.out.println("ZIP: " + zip + " (" + Files.size(zip) / (1024 * 1024) + " MiB) sha256=" + sha);
    }

    /** ADR-0017: another JDK means another launcher, whose behaviour has to be proved again. */
    private static String checkJdk(boolean anyJdk) throws IOException {
        Map<String, String> verified;
        try (Stream<String> lines = Files.lines(SOURCE.resolve("verified-jdk.txt"))) {
            verified = lines.filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split("=", 2))
                    .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));
        }
        String version = System.getProperty("java.version");
        String vendor = System.getProperty("java.vendor");
        boolean same = version.equals(verified.get("java.version")) && vendor.equals(verified.get("java.vendor"));
        String jdk = vendor + " " + version;
        if (!same && !anyJdk) {
            throw new IllegalStateException("This JDK is " + jdk + "; packaging/windows/verified-jdk.txt names "
                    + verified.get("java.vendor") + " " + verified.get("java.version")
                    + ". Build with -Dprism.package.unverifiedJdk=true, run verify-image.ps1 on the result, and"
                    + " update verified-jdk.txt only when it passes (ADR-0017).");
        }
        return jdk;
    }

    /** The class path gets the JavaFX jars for this platform; those without a classifier are empty stubs. */
    private static void copyDependencies(Path input) throws IOException {
        try (Stream<Path> jars = Files.list(TARGET.resolve("package-dependencies"))) {
            for (Path jar : (Iterable<Path>) jars::iterator) {
                String name = jar.getFileName().toString();
                if (!name.matches("javafx-[a-z]+-[0-9.]+\\.jar")) {
                    Files.copy(jar, input.resolve(name));
                }
            }
        }
    }

    private static List<String> readModules() throws IOException {
        try (Stream<String> lines = Files.lines(SOURCE.resolve("modules.txt"))) {
            return lines.filter(line -> !line.isBlank()).toList();
        }
    }

    private static void run(String tool, String... args) {
        ToolProvider provider =
                ToolProvider.findFirst(tool).orElseThrow(() -> new IllegalStateException("This JDK has no " + tool));
        int exit = provider.run(new PrintWriter(System.out, true), new PrintWriter(System.err, true), args);
        if (exit != 0) {
            throw new IllegalStateException(tool + " ended with " + exit);
        }
    }

    private static void addNoRestart(Path cfg) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(cfg));
        int section = lines.indexOf("[Application]");
        if (section < 0) {
            throw new IllegalStateException(cfg + " has no [Application] section");
        }
        lines.add(section + 1, NO_RESTART);
        Files.write(cfg, lines);
    }

    /** What can be told from the files. A failure here means this JDK's jpackage is not the one ADR-0017 knows. */
    private static void check(Path image, List<String> modules) throws IOException {
        for (String launcher : List.of("PRISM.exe", "prism-demo.exe")) {
            byte[] bytes = Files.readAllBytes(image.resolve(launcher));
            String key = "win.norestart";
            if (!contains(bytes, key.getBytes(StandardCharsets.US_ASCII))
                    && !contains(bytes, key.getBytes(StandardCharsets.UTF_16LE))) {
                throw new IllegalStateException(launcher + " does not name " + key + ": ADR-0017 no longer holds.");
            }
        }
        for (String cfg : List.of("PRISM.cfg", "prism-demo.cfg")) {
            String text = Files.readString(image.resolve("app").resolve(cfg));
            if (!text.contains(NO_RESTART)) {
                throw new IllegalStateException(cfg + " lacks " + NO_RESTART);
            }
            if (text.matches("(?s).*[A-Za-z]:\\\\.*")) {
                throw new IllegalStateException(cfg + " names an absolute path");
            }
        }
        String release = Files.readString(image.resolve("runtime").resolve("release"));
        for (String module : modules) {
            if (!release.contains(module)) {
                throw new IllegalStateException("The runtime lacks " + module);
            }
        }
        try (Stream<Path> files = Files.list(image.resolve("app"))) {
            List<String> stubs = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.matches("javafx-[a-z]+-[0-9.]+\\.jar"))
                    .toList();
            if (!stubs.isEmpty()) {
                throw new IllegalStateException("JavaFX stubs in the image: " + stubs);
            }
        }
    }

    private static boolean contains(byte[] bytes, byte[] part) {
        outer:
        for (int i = 0; i <= bytes.length - part.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (bytes[i + j] != part[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void zip(Path image, Path zip) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip));
                Stream<Path> files = Files.walk(image)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                String name = "PRISM/"
                        + image.relativize(file).toString().replace('\\', '/');
                out.putNextEntry(new ZipEntry(name));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Test code that runs on the image's own runtime; never part of the image or the ZIP. */
    private static void probeJar() throws IOException {
        Path out = WORK.resolve("verification");
        Files.createDirectories(out);
        Path classes = TARGET.resolve("test-classes");
        List<String> entries;
        try (Stream<Path> files = Files.list(classes.resolve("prism").resolve("srm").resolve("app"))) {
            entries = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith("ImageProbe"))
                    .map(name -> "prism/srm/app/" + name)
                    .toList();
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("No ImageProbe in target/test-classes: tests must be compiled.");
        }
        List<String> args = new ArrayList<>(
                List.of("--create", "--file", out.resolve("image-probe.jar").toString(), "-C", classes.toString()));
        args.addAll(entries.subList(0, 1));
        for (String entry : entries.subList(1, entries.size())) {
            args.addAll(List.of("-C", classes.toString(), entry));
        }
        run("jar", args.toArray(String[]::new));
        Files.copy(SOURCE.resolve("verify-image.ps1"), out.resolve("verify-image.ps1"));
    }

    private static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    // jpackage leaves the launchers read-only, which on Windows also refuses their deletion.
                    if (!file.toFile().setWritable(true)) {
                        throw new IOException("Cannot make writable: " + file);
                    }
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
