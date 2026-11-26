import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

/**
 * Builds the Linux application image and its tar.gz (ADR-0018). Run by the Maven profile
 * {@code package-image-linux}; JDK only, source-launched, so nothing of it reaches the product.
 *
 * <p>It stops with a non-zero exit at the first problem. The checks here are static; what the
 * launcher really does is proved by {@code verify-image.sh}, and an image is accepted only when that
 * passes on a Linux desktop.
 */
public final class PackageImage {

    private static final Path SOURCE = Path.of("packaging", "linux");
    private static final Path TARGET = Path.of("target");
    private static final Path WORK = TARGET.resolve("package");
    private static final List<String> LAUNCHERS = List.of("PRISM", "prism-demo");

    private PackageImage() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: PackageImage <version>");
        }
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("linux")) {
            throw new IllegalStateException("The Linux image is built on Linux (DEL-1: nothing is cross-built).");
        }
        String version = args[0];
        String jdk = System.getProperty("java.vendor") + " " + System.getProperty("java.version");

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
        Files.copy(SOURCE.resolve("README.txt"), image.resolve("README.txt"));
        check(image, modules);

        String arch = System.getProperty("os.arch").equals("amd64") ? "x64" : System.getProperty("os.arch");
        Path archive = WORK.resolve("prism-" + version + "-linux-" + arch + ".tar.gz");
        // tar keeps the launchers' execute bits and the runtime's symbolic links, which a ZIP would lose.
        command(images, "tar", "--owner=0", "--group=0", "--numeric-owner", "-czf",
                archive.toAbsolutePath().toString(), "PRISM");
        String sha = sha256(archive);
        Files.writeString(
                WORK.resolve(archive.getFileName() + ".sha256"),
                sha + "  " + archive.getFileName() + "\n",
                StandardCharsets.US_ASCII);

        probeJar();
        Files.writeString(WORK.resolve("BUILT_WITH.txt"), "Built with " + jdk + ".\n", StandardCharsets.US_ASCII);
        System.out.println("Image: " + image);
        System.out.println("Archive: " + archive + " (" + Files.size(archive) / (1024 * 1024) + " MiB) sha256=" + sha);
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

    private static void command(Path directory, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).inheritIO().start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(String.join(" ", command) + " ended with " + exit);
        }
    }

    /** What can be told from the files. */
    private static void check(Path image, List<String> modules) throws IOException {
        for (String launcher : LAUNCHERS) {
            Path binary = image.resolve("bin").resolve(launcher);
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(binary);
            if (!permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
                throw new IllegalStateException(binary + " is not executable");
            }
            String cfg = Files.readString(image.resolve("lib").resolve("app").resolve(launcher + ".cfg"));
            if (cfg.contains(System.getProperty("user.home"))) {
                throw new IllegalStateException(launcher + ".cfg names the build user's home");
            }
        }
        String release = Files.readString(image.resolve("lib").resolve("runtime").resolve("release"));
        for (String module : modules) {
            if (!release.contains(module)) {
                throw new IllegalStateException("The runtime lacks " + module);
            }
        }
        try (Stream<Path> files = Files.list(image.resolve("lib").resolve("app"))) {
            List<String> stubs = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.matches("javafx-[a-z]+-[0-9.]+\\.jar"))
                    .toList();
            if (!stubs.isEmpty()) {
                throw new IllegalStateException("JavaFX stubs in the image: " + stubs);
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

    /** Test code that runs on the image's own runtime; never part of the image or the archive. */
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
        Path script = out.resolve("verify-image.sh");
        Files.copy(SOURCE.resolve("verify-image.sh"), script);
        Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
