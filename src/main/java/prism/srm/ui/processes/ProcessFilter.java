package prism.srm.ui.processes;

/**
 * The text filter of the process table: a process stays when the text occurs in its name, in its
 * PID or in the name of its user.
 *
 * <p>Case is ignored character by character, without a locale, so the same text finds the same
 * processes on every machine: "WININIT" finds "wininit" also where the default locale is Turkish,
 * whose own rules would turn "I" into a dotless "i". Blanks around the text are ignored, and a
 * text of blanks only filters nothing.
 *
 * <p>The filter looks at the rows the table already holds. It reads nothing from the system.
 */
final class ProcessFilter {

    /** Lets every process through. */
    static final ProcessFilter NONE = new ProcessFilter("");

    private final String text;

    private ProcessFilter(String text) {
        this.text = text;
    }

    /** @param typed what the user typed; {@code null} is no text */
    static ProcessFilter of(String typed) {
        String text = typed == null ? "" : typed.strip();
        return text.isEmpty() ? NONE : new ProcessFilter(text);
    }

    /** Whether this filter can leave a process out. */
    boolean isActive() {
        return !text.isEmpty();
    }

    boolean matches(ProcessRow row) {
        if (text.isEmpty()) {
            return true;
        }
        return contains(row.snapshot().name(), text)
                || contains(row.pidText(), text)
                || (row.snapshot().user().isPresent()
                        && contains(row.snapshot().user().get(), text));
    }

    private static boolean contains(String whole, String part) {
        int last = whole.length() - part.length();
        for (int start = 0; start <= last; start++) {
            if (whole.regionMatches(true, start, part, 0, part.length())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProcessFilter filter && filter.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }
}
