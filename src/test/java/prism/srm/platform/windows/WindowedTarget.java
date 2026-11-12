package prism.srm.platform.windows;

import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * The smallest window an End Task can be shown on: a plain AWT frame of the JDK in a child JVM of
 * the test, which says on its output when it is shown and when it is asked to close, and then
 * exits with code 0. It touches nothing else, and exits by itself with code 3 after two minutes,
 * so a test that ends without asking it to close leaves nothing behind.
 */
public final class WindowedTarget {

    private WindowedTarget() {}

    public static void main(String[] arguments) {
        Frame frame = new Frame("Prism End Task target");
        frame.setSize(320, 160);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                System.out.println("shown");
                System.out.flush();
            }

            @Override
            public void windowClosing(WindowEvent event) {
                System.out.println("asked to close");
                System.out.flush();
                frame.dispose();
                System.exit(0);
            }
        });
        frame.setVisible(true);
        Thread deadline = new Thread(
                () -> {
                    try {
                        Thread.sleep(120_000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("not asked to close");
                    System.out.flush();
                    System.exit(3);
                },
                "windowed-target-deadline");
        deadline.setDaemon(true);
        deadline.start();
    }
}
