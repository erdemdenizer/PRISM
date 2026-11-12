package prism.srm.ui.shell;

import prism.srm.ui.icon.Icon;

/**
 * The pages of the primary navigation, in the order of the product specification, each with the
 * icon that stands for it (docs/UI_GUIDE.md, Shell).
 */
enum Destination {
    OVERVIEW("Overview", Icon.LAYOUT_DASHBOARD),
    PROCESSES("Processes", Icon.LIST),
    PERFORMANCE("Performance", Icon.ACTIVITY),
    LIMITS("Limits", Icon.SLIDERS_HORIZONTAL),
    OOM_GUARD("OOM Guard", Icon.SHIELD_ALERT),
    LOGS("Logs", Icon.SCROLL_TEXT),
    SETTINGS("Settings", Icon.SETTINGS);

    private final String title;
    private final Icon icon;

    Destination(String title, Icon icon) {
        this.title = title;
        this.icon = icon;
    }

    /** The name shown in the navigation. */
    String title() {
        return title;
    }

    /** The icon shown before the name. */
    Icon icon() {
        return icon;
    }
}
