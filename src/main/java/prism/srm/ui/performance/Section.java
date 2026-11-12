package prism.srm.ui.performance;

/**
 * The resources the Performance page has a view for, in the order they are listed. Public so that
 * another page can lead to one of them ({@link PerformancePage#select}).
 */
public enum Section {
    CPU("CPU"),
    MEMORY("Memory"),
    DISK("Disk"),
    NETWORK("Network");

    private final String title;

    Section(String title) {
        this.title = title;
    }

    /** The name shown on the page. */
    String title() {
        return title;
    }
}
