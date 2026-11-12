package prism.srm.platform.windows;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import prism.srm.demo.DemoWorkload;

/**
 * A parent for the tests of a target that exits while a process it started lives on (plan §11.3 E7
 * and E2 d): driven by its standard input, it starts the demo workload with the arguments given on
 * "spawn" and says {@code child=<pid>}, and exits on "exit" without ending that child, which the test
 * ends itself. It uses no processor time of note while it waits.
 */
public final class SpawningTarget {

    private SpawningTarget() {}

    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
        System.out.println("ready");
        System.out.flush();
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("exit")) {
                return;
            }
            if (line.startsWith("spawn")) {
                String java =
                        Path.of(System.getProperty("java.home"), "bin", "java").toString();
                String classes = Path.of(DemoWorkload.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI())
                        .toString();
                List<String> command = new ArrayList<>(List.of(java, "-cp", classes, DemoWorkload.class.getName()));
                command.addAll(List.of(line.substring("spawn".length()).strip().split(" ")));
                Process child = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                System.out.println("child=" + child.pid());
                System.out.flush();
            }
        }
    }
}
