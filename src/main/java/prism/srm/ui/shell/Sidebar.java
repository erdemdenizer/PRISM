package prism.srm.ui.shell;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import prism.srm.ui.icon.IconView;

/**
 * The primary navigation, UI lab v2 (owner decision K1): one list of the seven destinations in two
 * forms, chosen by the shell for the width of the window ({@link Mode}). A wide window has the
 * {@linkplain Mode#SIDEBAR sidebar}: icon and name side by side. Every other window has the {@linkplain
 * Mode#RAIL rail}: each icon with its name under it. Every form names its destinations (owner decision
 * after fix round H: a name shown only on hover was not understood); beside the rail, the pointer or the
 * keyboard on an entry also shows its name with its shortcut ({@link RailName}). The form changes by
 * pseudo-classes and the content display of the entries; no node is built again.
 *
 * <p>The seven destinations stand in three groups by how they are used: what is watched (Overview,
 * Processes, Performance), what acts on processes (Limits, OOM Guard), and the record of what was done
 * (Logs, read after acting). Settings is pinned to the bottom. An entry's name is its accessible text,
 * its shortcut its accessible help.
 *
 * <p>The selected entry has the selection surface, its icon in the accent color, its name in SemiBold,
 * and a 3 px accent bar at the list's left edge (shell.css); a badge (a dot on the upper right corner of
 * the icon) marks a destination only for a real state, as memory pressure on OOM Guard ({@link
 * #setBadge}). With names, the shortcut of an entry shows at its right end while the pointer is on it
 * or the keyboard focus is.
 *
 * <p>All seven are always listed. A destination whose page does not exist is disabled, and one line
 * below the list says what the dimmed entries mean; a disabled control can show no tooltip of its own.
 *
 * <p>Keyboard: the list is one stop in the Tab order, at the selected entry. The arrow keys move the
 * selection to the next entry that is available, which is the stock behavior of toggle buttons in a
 * group. An entry pressed with the mouse takes the focus, so the keys go on from it. Ctrl+1 to Ctrl+7
 * are handled by the shell ({@link Shortcuts}).
 */
final class Sidebar extends VBox {

    /** The forms of the navigation. */
    enum Mode {
        /** Each icon with its name under it, {@code Breakpoints.RAIL_WIDTH} wide. */
        RAIL,
        /** Icon and name side by side, {@code Breakpoints.SIDEBAR_WIDTH} wide. */
        SIDEBAR
    }

    /** The size of the icon of an entry, in every form. */
    private static final double ICON_SIZE = 20;

    /** The dot of a badge: its size, and how far it stands out of the icon's upper right corner. */
    private static final double BADGE_SIZE = 7;

    private static final double BADGE_OUTSET = 2;

    /** The sidebar: names beside the icons. */
    private static final PseudoClass LABELLED = PseudoClass.getPseudoClass("labelled");
    /** An entry with a badge; ShellOomBadgeSceneTest reads the state of the entry by it. */
    private static final PseudoClass BADGED = PseudoClass.getPseudoClass("badged");
    /** A badge for a state that is an error (the Emergency of OOM Guard), in the error color. */
    private static final PseudoClass BADGED_ERROR = PseudoClass.getPseudoClass("badged-error");

    private final ToggleGroup group = new ToggleGroup();
    private final Map<Destination, ToggleButton> entries = new EnumMap<>(Destination.class);
    private final Map<Destination, Region> badges = new EnumMap<>(Destination.class);
    private final Map<Destination, String> badgeTexts = new EnumMap<>(Destination.class);
    private final ReadOnlyObjectWrapper<Destination> selected = new ReadOnlyObjectWrapper<>();
    private final Set<Destination> available;
    private final RailName name = new RailName(this);
    private Mode mode = Mode.RAIL;
    /**
     * Whether the last key of the window moved the focus (Tab, an arrow key, Home, End). A press of the
     * mouse or any other key, Ctrl+1 to Ctrl+7 among them, clears it: the focus they bring to the rail is
     * no keyboard user's walk through it, and its name stayed over the page then (SH-03). So does no
     * input at all, as the focus that lands on the rail as the window opens or through UI Automation.
     */
    private boolean keyboardMoved;

    private final EventHandler<MouseEvent> pointer = event -> {
        keyboardMoved = false;
        name.hideAll();
    };
    private final EventHandler<KeyEvent> keys = event -> keyboardMoved = movesFocus(event);
    // The name is a popup at a place on the screen: it would stay there when the window moves or changes.
    private final InvalidationListener windowChanged = observable -> name.hideAll();
    private Window window;
    private final ChangeListener<Window> windowOf = (observable, before, after) -> follow(after);
    private final ChangeListener<Node> focusOwner = (observable, before, after) -> followFocus(after);

    /** @param available the destinations that have a page; the first of them is selected */
    Sidebar(Set<Destination> available) {
        this.available = Set.copyOf(available);
        getStyleClass().addAll("sidebar", "nav-rail");
        setAccessibleText("Navigation");
        for (Destination destination : Destination.values()) {
            if (destination == Destination.LIMITS || destination == Destination.LOGS) {
                getChildren().add(separator());
            }
            if (destination == Destination.SETTINGS) {
                Region push = new Region();
                push.getStyleClass().add("nav-push");
                VBox.setVgrow(push, Priority.ALWAYS);
                getChildren().add(push);
            }
            ToggleButton entry = newEntry(destination, this.available.contains(destination));
            entries.put(destination, entry);
            getChildren().add(entry);
            if (group.getSelectedToggle() == null && !entry.isDisable()) {
                group.selectToggle(entry);
                selected.set(destination);
            }
        }
        group.selectedToggleProperty().addListener((observable, before, after) -> {
            if (after == null) {
                // A toggle button is switched off by a click on it; a page list always has a
                // selection.
                if (before != null) {
                    group.selectToggle(before);
                }
            } else {
                selected.set((Destination) after.getUserData());
            }
        });

        sceneProperty().addListener((observable, before, after) -> {
            if (before != null) {
                before.removeEventFilter(MouseEvent.MOUSE_PRESSED, pointer);
                before.removeEventFilter(KeyEvent.KEY_PRESSED, keys);
                before.focusOwnerProperty().removeListener(focusOwner);
                before.windowProperty().removeListener(windowOf);
            }
            // Out of the window or into another: no name stays.
            name.hideAll();
            follow(after == null ? null : after.getWindow());
            if (after != null) {
                after.addEventFilter(MouseEvent.MOUSE_PRESSED, pointer);
                after.addEventFilter(KeyEvent.KEY_PRESSED, keys);
                after.focusOwnerProperty().addListener(focusOwner);
                after.windowProperty().addListener(windowOf);
            }
        });

        if (this.available.size() < Destination.values().length) {
            // Directly below the entries it explains.
            Label legend = new Label("Dimmed pages are not available in this version.");
            legend.getStyleClass().addAll("sidebar-legend", "text-caption", "text-muted");
            legend.setWrapText(true);
            legend.setMinHeight(Region.USE_PREF_SIZE);
            getChildren().add(legend);
        }
    }

    /** The selected destination; it changes when another entry is selected. */
    ReadOnlyObjectProperty<Destination> selectedProperty() {
        return selected.getReadOnlyProperty();
    }

    /** Returns the selected destination. */
    Destination selected() {
        return selected.get();
    }

    /** Selects a destination if it has a page; returns whether it is selected now. */
    boolean select(Destination destination) {
        ToggleButton entry = entries.get(destination);
        if (entry.isDisable()) {
            return false;
        }
        group.selectToggle(entry);
        return true;
    }

    /** Returns the entry of a destination, for tests that drive it by keyboard. */
    ToggleButton entry(Destination destination) {
        return entries.get(destination);
    }

    /** The entry that is selected: where the focus goes when the navigation is entered. */
    ToggleButton selectedEntry() {
        return entries.get(selected());
    }

    /**
     * Gives the list one of its forms. The entries change their content display and the list its
     * pseudo-classes; nothing is built again. A name beside the rail goes.
     */
    void setMode(Mode value) {
        if (value == mode) {
            return;
        }
        mode = value;
        name.hideAll();
        boolean sidebar = value == Mode.SIDEBAR;
        pseudoClassStateChanged(LABELLED, sidebar);
        for (ToggleButton entry : entries.values()) {
            entry.setContentDisplay(sidebar ? ContentDisplay.LEFT : ContentDisplay.TOP);
        }
    }

    Mode mode() {
        return mode;
    }

    /**
     * Marks a destination with a dot for a real state, and says the state in its accessible text;
     * empty takes the mark away. A state that is an error takes the error color, any other the warning
     * color, as the status bar says it.
     */
    void setBadge(Destination destination, Optional<String> state, boolean error) {
        Region badge = badges.get(destination);
        badge.setVisible(state.isPresent());
        ToggleButton entry = entries.get(destination);
        entry.pseudoClassStateChanged(BADGED, state.isPresent());
        entry.pseudoClassStateChanged(BADGED_ERROR, state.isPresent() && error);
        state.ifPresentOrElse(text -> badgeTexts.put(destination, text), () -> badgeTexts.remove(destination));
        entry.setAccessibleText(accessibleText(destination, available.contains(destination)));
    }

    /** The entry whose name shows beside the rail because the keyboard reached it, for tests; empty for none. */
    Optional<Destination> nameShownForKeyboard() {
        return name.shownFor(RailName.Cause.KEYBOARD).map(entry -> (Destination) entry.getUserData());
    }

    /** The entry whose name shows beside the rail because the pointer rests on it, for tests; empty for none. */
    Optional<Destination> nameShownForPointer() {
        return name.shownFor(RailName.Cause.POINTER).map(entry -> (Destination) entry.getUserData());
    }

    /** The name beside the rail, for tests. */
    RailName railName() {
        return name;
    }

    /**
     * An entry of the rail that Tab or an arrow key reaches shows its name beside the rail at once; the
     * name goes when the focus leaves it (A-M7). The scene says which control has the focus and which
     * input came last, whether the window has the focus or not.
     */
    private void followFocus(Node owner) {
        name.focusMovedTo(owner);
        if (mode != Mode.RAIL
                || !keyboardMoved
                || !(owner instanceof ToggleButton entry)
                || !entries.containsValue(entry)) {
            return;
        }
        Destination destination = (Destination) entry.getUserData();
        name.keyboardReached(entry, destination.title(), Shortcuts.text(destination));
    }

    /** Whether a key moves the focus by the stock traversal: Tab, an arrow key, Home or End, alone or with Shift. */
    private static boolean movesFocus(KeyEvent event) {
        if (event.isShortcutDown() || event.isAltDown()) {
            return false;
        }
        return switch (event.getCode()) {
            case TAB, UP, DOWN, LEFT, RIGHT, HOME, END, KP_UP, KP_DOWN, KP_LEFT, KP_RIGHT -> true;
            default -> false;
        };
    }

    /** Hides the name when the window moves, changes its size or loses the focus. */
    private void follow(Window next) {
        if (window != null) {
            window.xProperty().removeListener(windowChanged);
            window.yProperty().removeListener(windowChanged);
            window.widthProperty().removeListener(windowChanged);
            window.heightProperty().removeListener(windowChanged);
            window.focusedProperty().removeListener(windowChanged);
        }
        window = next;
        if (window != null) {
            window.xProperty().addListener(windowChanged);
            window.yProperty().addListener(windowChanged);
            window.widthProperty().addListener(windowChanged);
            window.heightProperty().addListener(windowChanged);
            window.focusedProperty().addListener(windowChanged);
        }
    }

    /** The state a badge says, for tests; empty when there is none. */
    Optional<String> badge(Destination destination) {
        return Optional.ofNullable(badgeTexts.get(destination));
    }

    private String accessibleText(Destination destination, boolean hasPage) {
        if (!hasPage) {
            return destination.title() + ", not available in this version";
        }
        String state = badgeTexts.get(destination);
        return state == null ? destination.title() : destination.title() + ", " + state;
    }

    private static Region separator() {
        Region line = new Region();
        line.getStyleClass().add("nav-separator");
        line.setMinHeight(Region.USE_PREF_SIZE);
        line.setMaxHeight(Region.USE_PREF_SIZE);
        return line;
    }

    private ToggleButton newEntry(Destination destination, boolean hasPage) {
        Region badge = new Region();
        badge.getStyleClass().add("nav-badge");
        badge.setManaged(false);
        badge.setVisible(false);
        badges.put(destination, badge);
        String shortcut = Shortcuts.text(destination);
        // The shortcut at the right end of a row with names; shell.css shows it under the pointer and
        // with the keyboard focus, and never in the rail, where the name beside the rail says it.
        Label keysHint = new Label(shortcut);
        keysHint.getStyleClass().add("nav-shortcut");
        keysHint.setManaged(false);
        keysHint.setMouseTransparent(true);
        EntryGraphic graphic = new EntryGraphic(new IconView(destination.icon(), ICON_SIZE), badge, keysHint);
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);

        ToggleButton entry = new ToggleButton(destination.title(), graphic);
        entry.widthProperty().addListener(observable -> graphic.requestLayout());
        entry.setContentDisplay(ContentDisplay.TOP);
        entry.getStyleClass().setAll("nav-item");
        entry.setUserData(destination);
        entry.setToggleGroup(group);
        entry.setDisable(!hasPage);
        entry.setMnemonicParsing(false);
        entry.setAccessibleText(accessibleText(destination, hasPage));
        entry.setAccessibleHelp(shortcut);

        // One Tab stop for the whole list, at the selected entry; the arrow keys reach the rest.
        entry.focusTraversableProperty().bind(entry.selectedProperty());
        // A button takes the focus on a press only while it is a Tab stop, which an entry that is not
        // selected is not: without this the focus stayed on the entry before, and the arrow keys
        // moved from there (found by the image verification with real keys).
        entry.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.isPrimaryButtonDown()) {
                entry.requestFocus();
            }
        });
        // The name beside the rail, for the pointer (RailName); the list with names needs none.
        entry.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> {
            if (mode == Mode.RAIL) {
                name.pointerEntered(entry, destination.title(), shortcut);
            }
        });
        entry.addEventHandler(MouseEvent.MOUSE_EXITED, event -> name.pointerExited(entry));
        return entry;
    }

    /**
     * The graphic of an entry: its icon, at the icon's size, with the dot of a badge on its upper right
     * corner and the shortcut at the right end of the entry. Its size is the icon's, not the entry's: a
     * graphic alone in a button is given all the button has otherwise, and the dot stood at the corner of
     * the entry, away from the icon (SH-04). The dot and the shortcut take no room.
     */
    private static final class EntryGraphic extends StackPane {

        private final Region badge;
        private final Label keysHint;

        EntryGraphic(Node icon, Region badge, Label keysHint) {
            super(icon, badge, keysHint);
            this.badge = badge;
            this.keysHint = keysHint;
            getStyleClass().add("nav-graphic");
            setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            badge.resizeRelocate(getWidth() - BADGE_SIZE + BADGE_OUTSET, -BADGE_OUTSET, BADGE_SIZE, BADGE_SIZE);
            // The shortcut ends at the right padding of the entry, centred on the line of the icon.
            double width = keysHint.prefWidth(-1);
            double height = keysHint.prefHeight(width);
            double right = getParent() instanceof Region entry
                    ? entry.getWidth() - entry.snappedRightInset() - getLayoutX()
                    : getWidth();
            keysHint.resizeRelocate(right - width, (getHeight() - height) / 2, width, height);
        }
    }
}
