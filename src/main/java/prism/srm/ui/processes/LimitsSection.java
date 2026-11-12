package prism.srm.ui.processes;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import javafx.beans.Observable;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import prism.srm.ui.icon.Icon;
import prism.srm.ui.icon.IconView;

/**
 * UI lab v2: "Limits & protection" in the narrow inspector: what Prism does to the selected process
 * (its managed mark, or that it is not managed), and the choices the context menu of the table has
 * for it (Manage, Set CPU limit…, Add to and Remove from OOM management, Unmanage), as buttons.
 *
 * <p>Presentation only: each button is enabled, disabled and explained by {@link ManagedActions},
 * exactly as the menu item of the same name, and does what that item does. A disabled button is
 * never hidden (CAP-2); the reasons of the disabled ones stand in the line under them, each once,
 * so that no reason is only in a tooltip.
 *
 * <p>Fix round H (PR-03): in a frame too low for the section whole it is <b>compact</b>: one menu button
 * named after the section, "Limits &amp; protection", whose items are the same five choices with the same
 * words, enabled and disabled as the buttons; the state of the process beside it; the reasons of the
 * disabled choices under it, on screen as before (CAP-2). Every node is made once, here; compact or not
 * only changes what is shown.
 */
final class LimitsSection extends VBox {

    private final ManagedActions managed;
    private final Label reasons = new Label();
    private final Label state = new Label();
    private final Label caption = new Label("Limits & protection");
    private final GridPane buttons = new GridPane();
    /** Compact: the menu with the five choices and the state beside it. */
    private final HBox compactRow = new HBox();

    private final MenuButton menu = new MenuButton("Limits & protection");
    private final Label compactState = new Label();
    private boolean compact;

    /**
     * @param managed the managed choices of the selected process, with its managed mark
     * @param choose carries out a choice, as the menu item of the same name does
     */
    LimitsSection(ManagedActions managed, Consumer<ManagedActions.Choice> choose) {
        this.managed = managed;
        getStyleClass().add("inspector-limits");
        // With no process selected the line keeps its place and says nothing about one (PR-15).
        state.textProperty()
                .bind(javafx.beans.binding.Bindings.createStringBinding(
                        () -> !managed.selectedProperty().get()
                                ? "–"
                                : managed.markProperty().get().isEmpty()
                                        ? "Not managed by Prism."
                                        : managed.markProperty().get(),
                        managed.selectedProperty(),
                        managed.markProperty()));
        state.getStyleClass().addAll("inspector-limits-state", "text-small");
        state.setWrapText(true);
        // Three lines, each with its caption: the short words of a button are read with it; a
        // screen reader has the whole name of the menu item.
        buttons.getStyleClass().add("inspector-limits-buttons");
        buttons.addRow(
                0,
                rowCaption("Managing"),
                new HBox(
                        8,
                        held("Manage", "Manage", ManagedActions.Choice.MANAGE, choose),
                        held("Unmanage", "Unmanage", ManagedActions.Choice.UNMANAGE, choose)));
        buttons.addRow(
                1,
                rowCaption("CPU limit"),
                new HBox(8, held("Set…", "Set CPU limit…", ManagedActions.Choice.SET_CPU_LIMIT, choose)));
        buttons.addRow(
                2,
                rowCaption("OOM Guard"),
                new HBox(
                        8,
                        held("Add", "Add to OOM management", ManagedActions.Choice.OOM_MANAGE, choose),
                        held("Remove", "Remove from OOM management", ManagedActions.Choice.OOM_UNMANAGE, choose)));
        reasons.getStyleClass().addAll("inspector-limits-reasons", "text-small", "text-muted");
        reasons.setWrapText(true);
        reasons.setMinHeight(Region.USE_PREF_SIZE);
        reasons.visibleProperty().bind(reasons.textProperty().isNotEmpty());
        reasons.managedProperty().bind(reasons.visibleProperty());
        for (ManagedActions.Choice choice : ManagedActions.Choice.values()) {
            managed.disabledProperty(choice).addListener(this::showReasons);
            managed.explanationProperty(choice).addListener(this::showReasons);
        }
        showReasons(null);
        caption.getStyleClass().add("inspector-section-caption");
        buildCompactRow(choose);
        getChildren().setAll(caption, state, buttons, compactRow, reasons);
        showCompact();
    }

    /** The menu of the compact section: the five choices, in the order and with the names of the context menu. */
    private void buildCompactRow(Consumer<ManagedActions.Choice> choose) {
        menu.getStyleClass().addAll("inspector-limits-menu", "process-action");
        menu.setAccessibleText("Limits & protection: Manage, Set CPU limit, OOM Guard");
        menu.setGraphic(new IconView(Icon.CHEVRON_DOWN, 12));
        menu.setContentDisplay(ContentDisplay.RIGHT);
        menu.setGraphicTextGap(4);
        menu.setMinWidth(Region.USE_PREF_SIZE);
        menu.getItems()
                .setAll(
                        item("Manage", ManagedActions.Choice.MANAGE, choose),
                        item("Unmanage", ManagedActions.Choice.UNMANAGE, choose),
                        new SeparatorMenuItem(),
                        item("Set CPU limit…", ManagedActions.Choice.SET_CPU_LIMIT, choose),
                        new SeparatorMenuItem(),
                        item("Add to OOM management", ManagedActions.Choice.OOM_MANAGE, choose),
                        item("Remove from OOM management", ManagedActions.Choice.OOM_UNMANAGE, choose));
        Tooltip tooltip = new Tooltip("Manage, Unmanage, Set CPU limit…, Add to and Remove from OOM management");
        tooltip.setShowDuration(Duration.seconds(10));
        menu.setTooltip(tooltip);
        compactState.textProperty().bind(state.textProperty());
        compactState.getStyleClass().addAll("inspector-limits-state", "text-small");
        compactState.setWrapText(true);
        compactState.setMinWidth(0);
        HBox.setHgrow(compactState, Priority.ALWAYS);
        compactRow.getStyleClass().add("inspector-limits-compact");
        compactRow.getChildren().setAll(menu, compactState);
    }

    private MenuItem item(String text, ManagedActions.Choice choice, Consumer<ManagedActions.Choice> choose) {
        MenuItem item = new MenuItem(text);
        item.disableProperty().bind(managed.disabledProperty(choice));
        item.setOnAction(event -> choose.accept(choice));
        return item;
    }

    /**
     * Compact, in a frame too low for the section whole: the menu and the state on one line, the reasons
     * under them. The focus on a button that goes out of view goes to the menu that has its choice.
     */
    void setCompact(boolean value) {
        if (value == compact) {
            return;
        }
        boolean focusInButtons =
                value && getScene() != null && isInside(getScene().getFocusOwner(), buttons);
        compact = value;
        showCompact();
        if (focusInButtons) {
            menu.requestFocus();
        }
    }

    boolean isCompact() {
        return compact;
    }

    private void showCompact() {
        caption.setVisible(!compact);
        caption.setManaged(!compact);
        state.setVisible(!compact);
        state.setManaged(!compact);
        buttons.setVisible(!compact);
        buttons.setManaged(!compact);
        compactRow.setVisible(compact);
        compactRow.setManaged(compact);
    }

    private static boolean isInside(javafx.scene.Node node, javafx.scene.Node container) {
        for (javafx.scene.Node at = node; at != null; at = at.getParent()) {
            if (at == container) {
                return true;
            }
        }
        return false;
    }

    /**
     * As wide as its caption and its buttons, never as wide as the sentences it shows, which wrap: the
     * column it stands in under the table does not move with the process selected (PR-10).
     */
    @Override
    protected double computePrefWidth(double height) {
        double widest = 0;
        for (javafx.scene.Node child : getChildren()) {
            if (child != reasons && child != state && child.isManaged()) {
                widest = Math.max(widest, child.prefWidth(-1));
            }
        }
        return snappedLeftInset() + widest + snappedRightInset();
    }

    @Override
    protected double computeMinWidth(double height) {
        return computePrefWidth(height);
    }

    /** For tests: the line of the reasons. */
    Label reasons() {
        return reasons;
    }

    /** The reasons of the disabled choices, each once, in the order of the buttons. */
    private void showReasons(Observable ignored) {
        Set<String> distinct = new LinkedHashSet<>();
        for (ManagedActions.Choice choice : ManagedActions.Choice.values()) {
            if (managed.disabledProperty(choice).get()) {
                distinct.add(managed.explanationProperty(choice).get());
            }
        }
        reasons.setText(String.join(" ", distinct));
    }

    private static Label rowCaption(String text) {
        Label caption = new Label(text);
        caption.getStyleClass().addAll("inspector-limits-row-caption", "text-small", "text-muted");
        caption.setMinWidth(Region.USE_PREF_SIZE);
        return caption;
    }

    private StackPane held(
            String text, String name, ManagedActions.Choice choice, Consumer<ManagedActions.Choice> choose) {
        Button button = new Button(text);
        button.getStyleClass().add("inspector-limit-action");
        button.setAccessibleText(name);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.disableProperty().bind(managed.disabledProperty(choice));
        button.accessibleHelpProperty().bind(managed.explanationProperty(choice));
        button.setOnAction(event -> choose.accept(choice));
        Tooltip tooltip = new Tooltip();
        tooltip.textProperty().bind(managed.explanationProperty(choice));
        tooltip.setShowDuration(Duration.seconds(10));
        // A disabled button shows no tooltip of its own: the holder shows it whether it is enabled or not.
        StackPane holder = new StackPane(button);
        holder.getStyleClass().add("process-action-holder");
        holder.setMinWidth(Region.USE_PREF_SIZE);
        Tooltip.install(holder, tooltip);
        return holder;
    }
}
