package prism.srm.ui.control;

import java.util.Objects;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The first line of a page: its title at the left and, at the right, what the page puts there,
 * such as the number of processes. One line, 28 px high, so the page below begins at the same
 * height on every page (docs/UI_GUIDE.md, Page header).
 */
public final class PageHeader extends HBox {

    private final Region gap = new Region();

    public PageHeader(String title) {
        Objects.requireNonNull(title, "title");
        getStyleClass().add("page-header");
        Label name = new Label(title);
        name.getStyleClass().add("text-page-title");
        name.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(gap, Priority.ALWAYS);
        getChildren().setAll(name, gap);
    }

    /**
     * Puts a node at the right end of the line, in place of the one there. For something that
     * belongs to the page as a whole, not to a part of it.
     */
    public void setTrailing(Node node) {
        Objects.requireNonNull(node, "node");
        // Whatever is after the gap is the trailing node; the children are the only record of it.
        int after = getChildren().indexOf(gap) + 1;
        getChildren().remove(after, getChildren().size());
        getChildren().add(node);
    }
}
