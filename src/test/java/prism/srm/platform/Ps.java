package prism.srm.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * One column of {@code ps} for one process: a read-only system tool that reads the system's own
 * process table, not through Prism's native code, for comparing with what Prism reads. Test
 * diagnostics only; production code never runs a command (SAFE-5).
 */
public final class Ps {

    private Ps() {}

    /**
     * The value of the column for the process, without a header ({@code column=}) and without the
     * padding ps aligns it with.
     */
    public static String column(long pid, String column) throws IOException, InterruptedException {
        Process ps = new ProcessBuilder("ps", "-o", column + "=", "-p", Long.toString(pid)).start();
        // The answer is one short line, well within a pipe's buffer: ps does not wait to be read.
        if (!ps.waitFor(10, TimeUnit.SECONDS)) {
            ps.destroyForcibly();
            throw new IOException("ps did not answer for " + pid + " within 10 s");
        }
        String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
        String err = new String(ps.getErrorStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
        if (ps.exitValue() != 0 || out.isEmpty()) {
            throw new IOException("ps reported no " + column + " for " + pid + ": exit " + ps.exitValue() + ", " + err);
        }
        return out;
    }
}
