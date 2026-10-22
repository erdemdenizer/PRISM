package prism.srm.ui.metric;

import javafx.beans.binding.BooleanBinding;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import prism.srm.ui.metric.PageStatus.PageState;

/**
 * The line at the top of a page that says why the page has no values. It takes room only while
 * there is something to say.
 */
public final class PageNotice extends Label {

    public PageNotice(PageStatus status) {
        textProperty().bind(status.errorProperty());
        getStyleClass().add("page-error");
        setWrapText(true);
        setMinHeight(Region.USE_PREF_SIZE);
        BooleanBinding failed = status.stateProperty().isEqualTo(PageState.ERROR);
        visibleProperty().bind(failed);
        managedProperty().bind(failed);
    }
}
