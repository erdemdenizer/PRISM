package prism.srm.ui.performance;

import prism.srm.domain.SystemSnapshot;

/**
 * What one section of the Performance page shows. Changed on the JavaFX Application Thread only,
 * by the view model of the page.
 */
interface SectionModel {

    /** Shows a snapshot. */
    void update(SystemSnapshot snapshot);

    /**
     * Takes every value, history and row off the section, because monitoring is not running: left
     * in place, they would look current.
     */
    void showNothing();
}
