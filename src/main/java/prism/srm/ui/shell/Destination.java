package prism.srm.ui.shell;

/** The pages of the primary navigation, in the order of the product specification. */
enum Destination {
    OVERVIEW("Overview"),
    PROCESSES("Processes"),
    PERFORMANCE("Performance"),
    LIMITS("Limits"),
    OOM_GUARD("OOM Guard"),
    LOGS("Logs"),
    SETTINGS("Settings");

    private final String title;

    Destination(String title) {
        this.title = title;
    }

    /** The name shown in the navigation. */
    String title() {
        return title;
    }
}
