package prism.srm.demo;

/** A command line that cannot be run. The message is what the person at the terminal reads. */
final class UsageException extends Exception {

    static final int EXIT_CODE = 2;

    UsageException(String message) {
        super(message);
    }
}
