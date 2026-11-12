package prism.srm.platform;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * A process that only holds threads, idle, so that a measurement can see how the cost of a
 * system-wide thread listing grows with the number of threads in the system. Prints "holding N"
 * once they all exist and exits after the given time.
 *
 * <p>Arguments: the number of threads, the seconds to live.
 */
public final class ThreadHolder {

    private ThreadHolder() {}

    public static void main(String[] args) throws InterruptedException {
        int count = Integer.parseInt(args[0]);
        long seconds = Long.parseLong(args[1]);
        CountDownLatch end = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Thread thread = new Thread(
                    () -> {
                        try {
                            end.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    "held-" + i);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        System.out.println("holding " + threads.size());
        System.out.flush();
        Thread.sleep(Duration.ofSeconds(seconds));
        end.countDown();
    }

    /** Starts a holder of that many threads and returns once they exist. */
    public static Process start(int count, long seconds) throws java.io.IOException {
        String javaBinary = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                .toString();
        ProcessBuilder builder = new ProcessBuilder(
                        javaBinary,
                        "-Xss144k",
                        "-cp",
                        System.getProperty("java.class.path"),
                        ThreadHolder.class.getName(),
                        Integer.toString(count),
                        Long.toString(seconds))
                .redirectErrorStream(true);
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"));
        Process process = builder.start();
        java.io.BufferedReader output = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
        String line = output.readLine();
        if (line == null || !line.startsWith("holding")) {
            process.destroyForcibly();
            throw new IllegalStateException("The thread holder did not start: " + line);
        }
        return process;
    }
}
