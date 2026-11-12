package prism.srm.ui.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.OomView;
import prism.srm.domain.ThemeMode;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.Typefaces;
import prism.srm.ui.control.PageHeader;
import prism.srm.ui.control.TextWidths;

/**
 * The Settings page (UI-7): the appearance, the folders of the logs and the settings, and a summary
 * of what this system offers. Automatic termination has no switch here: SET-2 keeps it where a
 * deliberate choice is made, on the OOM Guard page.
 *
 * <p>Layout and binding only. Every method is called on the JavaFX Application Thread.
 */
public final class SettingsPage extends VBox {

    /**
     * What the page shows at first, and what it changes and opens.
     *
     * @param appearance the appearance read at start-up
     * @param appearanceChange applies and saves a chosen appearance; returns at once
     * @param logFolder where the logs are, as text for the user
     * @param settingsFolder where the settings are, as text for the user
     * @param openLogFolder opens the log folder; returns at once
     * @param openSettingsFolder opens the settings folder; returns at once
     * @param loadProblem what was wrong with the settings file at start-up; empty when nothing
     */
    public record Hooks(
            ThemeMode appearance,
            Consumer<ThemeMode> appearanceChange,
            String logFolder,
            String settingsFolder,
            Runnable openLogFolder,
            Runnable openSettingsFolder,
            Optional<String> loadProblem) {

        public Hooks {
            Objects.requireNonNull(appearance, "appearance");
            Objects.requireNonNull(appearanceChange, "appearanceChange");
            Objects.requireNonNull(logFolder, "logFolder");
            Objects.requireNonNull(settingsFolder, "settingsFolder");
            Objects.requireNonNull(openLogFolder, "openLogFolder");
            Objects.requireNonNull(openSettingsFolder, "openSettingsFolder");
            Objects.requireNonNull(loadProblem, "loadProblem");
        }

        /** Hooks that change and open nothing, for a shell with no running application behind it. */
        public static Hooks inert(String logFolder) {
            return new Hooks(ThemeMode.SYSTEM, mode -> {}, logFolder, "", () -> {}, () -> {}, Optional.empty());
        }
    }

    /** What a folder row says when the application gave no folder, as a shell with nothing behind it. */
    static final String NO_FOLDER = "Not known in this window.";

    /** The padding of a segment of the appearance control on each side, as settings.css sets it. */
    private static final double SEGMENT_PADDING = 12;

    private static final String[] COLUMNS = {"Function", "Level", "How it works"};

    /** The least number of characters a line of a text block keeps: the low end of a readable measure. */
    private static final int DETAIL_LEAST_CHARACTERS = 45;

    /** The gap between the columns of the summary, on the spacing scale. */
    private static final double COLUMN_GAP = 16;

    private final SettingsViewModel model = new SettingsViewModel();
    private final Label appearanceProblem = problemLine("settings-save-problem");
    private final Label loadProblem = problemLine("settings-load-problem");
    private final Label folderProblem = problemLine("settings-folder-problem");
    private final Label capabilityStatus = new Label();
    private final VBox capabilities = new VBox();
    private final double functionWidth;
    private final double levelWidth;

    /** Where a path is copied to: the system clipboard, unless a test says otherwise. */
    private Consumer<String> clipboard = text -> {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    };

    public SettingsPage(Hooks hooks) {
        Objects.requireNonNull(hooks, "hooks");
        getStyleClass().add("settings");
        getStylesheets().add(Stylesheets.settings());

        capabilityStatus.getStyleClass().addAll("settings-capability-status", "text-small", "text-muted");
        capabilityStatus.setWrapText(true);
        capabilityStatus.setMinHeight(Region.USE_PREF_SIZE);

        // The summary as a table of three columns under their headers (LS-06), a row of three cells
        // per function. The first two are as wide as their widest value, measured from the font, so
        // they do not move as rows arrive and stand under one another from row to row.
        capabilities.getStyleClass().add("settings-capabilities");
        Font body = TextWidths.tableBody();
        Font header = TextWidths.tableHeader();
        functionWidth = widest(body, header, COLUMNS[0], SettingsViewModel.functions());
        levelWidth = widest(body, header, COLUMNS[1], SettingsViewModel.levels());

        show(hooks.loadProblem(), loadProblem);
        VBox summaryGroup = group(capabilityStatus, capabilities);
        VBox first = new VBox(
                section("Appearance", group(appearance(hooks)), appearanceProblem),
                section("Locations", group(locations(hooks)), folderProblem));
        first.getStyleClass().add("settings-column");
        VBox summary = section(
                "What this system offers",
                summaryGroup,
                note("One process can still refuse an action or a limit: the Processes and Limits"
                        + " pages say so for that process."));
        SectionColumns columns = new SectionColumns(first, summary, () -> leastColumn(summaryGroup));
        VBox.setVgrow(columns, Priority.ALWAYS);
        getChildren().setAll(new PageHeader("Settings"), columns);
        refreshCapabilities();
    }

    /**
     * The least width of a column of the page beside another (LS-17): the summary with its "How it
     * works" column still {@link #DETAIL_LEAST_CHARACTERS} characters wide, the low end of a readable
     * measure, measured from the font, inside the padding of its panel.
     */
    private double leastColumn(Region summaryGroup) {
        double detail = TextWidths.width(TextWidths.tableBody(), "x".repeat(DETAIL_LEAST_CHARACTERS));
        return summaryGroup.snappedLeftInset()
                + functionWidth
                + levelWidth
                + 2 * COLUMN_GAP
                + detail
                + summaryGroup.snappedRightInset();
    }

    /** Takes how each action is provided on this system. */
    public void showControlProfile(ControlProfile profile) {
        model.showProfile(profile);
        refreshCapabilities();
    }

    /** Takes what a limit is on this system. */
    public void showLimits(LimitsSnapshot snapshot) {
        model.showLimits(snapshot);
        refreshCapabilities();
    }

    /** Takes whether OOM Guard may decide by the readings of this system; no rows are built for nothing. */
    public void showOom(OomView view) {
        if (model.showSignal(view.signal())) {
            refreshCapabilities();
        }
    }

    /** Monitoring did not start: what it would have reported never comes, and the page says so. */
    public void showStartupFailure(String logFolder) {
        model.showStartupFailure(logFolder);
        refreshCapabilities();
    }

    /**
     * The end of a save: empty clears what was said about the last save, a problem replaces it. What
     * was wrong with the file at start-up stays where it is, by the settings folder (LS-08): it is not
     * about the appearance, and a later save does not undo it.
     */
    public void showSaveOutcome(Optional<String> problem) {
        show(problem, appearanceProblem);
    }

    /** The end of a request to open a folder. */
    public void showFolderOutcome(Optional<String> problem) {
        show(problem, folderProblem);
    }

    /** Sends a copied path elsewhere than the system clipboard; for tests. */
    void copyTo(Consumer<String> target) {
        clipboard = Objects.requireNonNull(target, "target");
    }

    private static VBox appearance(Hooks hooks) {
        ToggleGroup group = new ToggleGroup();
        HBox choices = new HBox();
        choices.getStyleClass().add("settings-choices");
        // Equal segments (LS-10), as wide as the widest name in the face the segments draw with.
        Font face = Font.font(Typefaces.MEDIUM_FAMILY, 13);
        double segment = 0;
        for (ThemeMode mode : ThemeMode.values()) {
            segment = Math.max(segment, TextWidths.width(face, name(mode)));
        }
        segment = Math.ceil((segment + 2 * SEGMENT_PADDING) / 4) * 4;
        for (ThemeMode mode : ThemeMode.values()) {
            RadioButton choice = new RadioButton(name(mode));
            choice.getStyleClass().add("settings-appearance");
            choice.setUserData(mode);
            choice.setToggleGroup(group);
            choice.setSelected(mode == hooks.appearance());
            choice.setAccessibleText("Appearance: " + name(mode));
            choice.setMinWidth(segment);
            choice.setPrefWidth(segment);
            choice.setMaxWidth(segment);
            choices.getChildren().add(choice);
        }
        // Added after the first selection: what was read at start-up is no change to save.
        group.selectedToggleProperty().addListener((observable, before, after) -> {
            if (after != null) {
                hooks.appearanceChange().accept((ThemeMode) after.getUserData());
            }
        });
        // A row of the group: what it sets at the left, the choice as a segmented control at the right.
        Label theme = new Label("Theme");
        theme.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(theme, gap, choices);
        row.getStyleClass().add("settings-row");
        VBox block = new VBox(row, note("System follows the operating system, also while Prism runs."));
        block.getStyleClass().add("settings-section");
        return block;
    }

    private static String name(ThemeMode mode) {
        return switch (mode) {
            case SYSTEM -> "System";
            case LIGHT -> "Light";
            case DARK -> "Dark";
        };
    }

    /**
     * The two folders, each a row as a system's settings show a location (LS-03): its name and, under
     * it, the whole path, wrapped where the row is narrow, never cut; its button at the right. What
     * was wrong with the settings file at start-up is said under the settings folder, next to the
     * button that opens it (LS-08).
     */
    private VBox locations(Hooks hooks) {
        VBox rows = new VBox(
                folder("Logs", "Open log folder", hooks.logFolder(), hooks.openLogFolder()),
                folder("Settings", "Open settings folder", hooks.settingsFolder(), hooks.openSettingsFolder()),
                loadProblem);
        rows.getStyleClass().add("settings-locations");
        return rows;
    }

    private HBox folder(String name, String action, String path, Runnable open) {
        Label label = new Label(name);
        label.getStyleClass().add("settings-folder-name");
        boolean known = !path.isBlank();
        // Plain text, not a field (LS-05): nothing here can be edited. It takes the keyboard, so Ctrl+C
        // and the context menu copy it, as a selectable field did; a screen reader reads its name and path.
        Label value = new Label(known ? path : NO_FOLDER);
        value.getStyleClass().addAll("settings-folder-path", "text-small", "text-muted");
        value.setWrapText(true);
        value.setMinHeight(Region.USE_PREF_SIZE);
        value.setMinWidth(0);
        value.setAccessibleText(name + " folder: " + (known ? path : NO_FOLDER));
        if (known) {
            value.setFocusTraversable(true);
            MenuItem copyPath = new MenuItem("Copy path");
            copyPath.setOnAction(event -> clipboard.accept(path));
            value.setContextMenu(new ContextMenu(copyPath));
            value.setOnMouseClicked(event -> value.requestFocus());
            value.setOnKeyPressed(event -> {
                if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                    clipboard.accept(path);
                    event.consume();
                }
            });
        }
        VBox text = new VBox(label, value);
        text.getStyleClass().add("settings-folder-text");
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button button = new Button(action);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.getStyleClass().add("settings-open");
        button.setOnAction(event -> open.run());
        HBox row = new HBox(text, button);
        row.getStyleClass().add("settings-folder");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void refreshCapabilities() {
        Optional<String> status = model.status();
        capabilityStatus.setText(status.orElse(""));
        capabilityStatus.setVisible(status.isPresent());
        capabilityStatus.setManaged(status.isPresent());
        capabilities.getChildren().clear();
        List<SettingsViewModel.Row> rows = model.summary();
        if (rows.isEmpty()) {
            return;
        }
        HBox headers = row(header(COLUMNS[0], functionWidth), header(COLUMNS[1], levelWidth), header(COLUMNS[2], -1));
        headers.getStyleClass().add("settings-capability-headers");
        Region rule = new Region();
        rule.getStyleClass().add("settings-capability-rule");
        capabilities.getChildren().addAll(headers, rule);
        for (SettingsViewModel.Row row : rows) {
            HBox line = row(
                    cell(row.function(), row, functionWidth),
                    cell(row.level(), row, levelWidth),
                    cell(row.detail(), row, -1));
            line.getStyleClass().add("settings-capability-row");
            // What a grid of labels cannot tell a screen reader (LS-06): the row is one element, before
            // its cells, with one sentence that names its columns, so a reader that moves from item to
            // item meets a function whole, with its level and how it works. Not a table role: JavaFX
            // gives that only to a control that answers for its rows, columns and selection.
            line.setAccessibleRole(AccessibleRole.TEXT);
            line.setAccessibleText(sentence(row));
            capabilities.getChildren().add(line);
        }
        // The rows name their columns; the line of headers says what the rows are.
        headers.setAccessibleRole(AccessibleRole.TEXT);
        headers.setAccessibleText(rows.size() + " functions, each with its level and how it works");
    }

    /** A row of the summary: three cells, the first two as wide as their columns, the last the rest. */
    private static HBox row(Label function, Label level, Label detail) {
        HBox.setHgrow(detail, Priority.ALWAYS);
        HBox row = new HBox(COLUMN_GAP, function, level, detail);
        row.setFillHeight(false);
        return row;
    }

    private static Label header(String text, double width) {
        Label title = new Label(text);
        title.getStyleClass().add("settings-capability-header");
        fix(title, width);
        return title;
    }

    /** A cell of a fixed column as wide as the column; the cell of the last column takes the rest. */
    private static void fix(Label cell, double width) {
        if (width >= 0) {
            cell.setMinWidth(width);
            cell.setPrefWidth(width);
            cell.setMaxWidth(width);
        } else {
            cell.setMinWidth(0);
            cell.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private static String sentence(SettingsViewModel.Row row) {
        String level = row.function() + ". " + COLUMNS[1] + ": " + row.level();
        return row.detail().isEmpty() ? level : level + ". " + COLUMNS[2] + ": " + row.detail();
    }

    private static Label cell(String text, SettingsViewModel.Row row, double width) {
        boolean wraps = width < 0;
        Label cell = new Label(text);
        cell.getStyleClass().add("settings-capability");
        if (!row.known()) {
            cell.getStyleClass().add("settings-capability-reading");
        } else if (!row.available()) {
            // The words say it too: never the color alone.
            cell.getStyleClass().add("settings-capability-unavailable");
        }
        cell.setWrapText(wraps);
        cell.setMinHeight(Region.USE_PREF_SIZE);
        fix(cell, width);
        return cell;
    }

    private static double widest(Font body, Font header, String title, List<String> values) {
        return TextWidths.column(body, header, title, values) - 2 * TextWidths.CELL_PADDING;
    }

    /**
     * The rows of a section on a panel of their own (UI lab v2: grouped rows, as system settings
     * are). The panel holds no live value, so it may have the surface and the hairline of a panel.
     */
    private static VBox group(Region... rows) {
        VBox group = new VBox(rows);
        group.getStyleClass().addAll("panel", "settings-group");
        return group;
    }

    private static VBox section(String title, Region... content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("text-section-title");
        VBox section = new VBox(heading);
        section.getChildren().addAll(content);
        section.getStyleClass().add("settings-section");
        return section;
    }

    private static Label note(String text) {
        Label note = new Label(text);
        note.getStyleClass().addAll("text-small", "text-muted");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);
        return note;
    }

    private static Label problemLine(String styleClass) {
        Label line = new Label();
        line.getStyleClass().addAll("settings-problem", styleClass, "text-small");
        line.setWrapText(true);
        line.setMinHeight(Region.USE_PREF_SIZE);
        line.setVisible(false);
        line.setManaged(false);
        return line;
    }

    private static void show(Optional<String> problem, Label line) {
        line.setText(problem.orElse(""));
        line.setVisible(problem.isPresent());
        line.setManaged(problem.isPresent());
    }

    /**
     * The sections of the page, one under another, or in two columns side by side where the page is
     * too low for them under one another and wide enough for two readable columns (LS-17): a low,
     * wide window shows the summary of what the system offers beside the appearance and the folders,
     * instead of under the fold with the right of the window empty. The order of the keyboard and of
     * a screen reader is the same either way: the first column, then the second.
     *
     * <p>The rule is the room, not a window size: two columns only while the sections under one another
     * would scroll, and only while each column keeps at least {@code leastColumn} (the summary with a
     * readable "How it works"). Each section keeps its readable measure (settings.css) in either form.
     * The gap between the columns is the gap between the sections of the first column.
     */
    private static final class SectionColumns extends Region {

        private final VBox first;
        private final Region second;
        private final DoubleSupplier leastColumn;

        SectionColumns(VBox first, Region second, DoubleSupplier leastColumn) {
            this.first = first;
            this.second = second;
            this.leastColumn = leastColumn;
            getStyleClass().add("settings-columns");
            getChildren().addAll(first, second);
        }

        /** Whether the sections stand side by side at this size of the content; for tests. */
        boolean sideBySide(double width, double height) {
            return besideFits(width) && stackedHeight(width) > height + 0.5;
        }

        private double gap() {
            return snapSpaceY(first.getSpacing());
        }

        private boolean besideFits(double width) {
            return width >= 2 * leastColumn.getAsDouble() + gap();
        }

        private double columnWidth(double width) {
            return snapSizeX((width - gap()) / 2);
        }

        private double secondWidth(double width) {
            return Math.min(width, second.maxWidth(-1));
        }

        private double stackedHeight(double width) {
            return first.prefHeight(width) + gap() + second.prefHeight(secondWidth(width));
        }

        private double besideHeight(double width) {
            double column = columnWidth(width);
            return Math.max(first.prefHeight(column), second.prefHeight(Math.min(column, second.maxWidth(-1))));
        }

        private double contentWidth(double width) {
            return width < 0
                    ? computePrefWidth(-1) - snappedLeftInset() - snappedRightInset()
                    : width - snappedLeftInset() - snappedRightInset();
        }

        @Override
        public Orientation getContentBias() {
            return Orientation.HORIZONTAL;
        }

        @Override
        protected double computeMinWidth(double height) {
            return snappedLeftInset() + Math.max(first.minWidth(-1), second.minWidth(-1)) + snappedRightInset();
        }

        @Override
        protected double computePrefWidth(double height) {
            return snappedLeftInset() + Math.max(first.prefWidth(-1), second.prefWidth(-1)) + snappedRightInset();
        }

        /** The least height is that of the columns side by side, where they may stand so. */
        @Override
        protected double computeMinHeight(double width) {
            double content = contentWidth(width);
            double least = besideFits(content)
                    ? Math.min(stackedHeight(content), besideHeight(content))
                    : stackedHeight(content);
            return snappedTopInset() + least + snappedBottomInset();
        }

        @Override
        protected double computePrefHeight(double width) {
            return snappedTopInset() + stackedHeight(contentWidth(width)) + snappedBottomInset();
        }

        @Override
        protected void layoutChildren() {
            double x = snappedLeftInset();
            double y = snappedTopInset();
            double width = getWidth() - x - snappedRightInset();
            double height = getHeight() - y - snappedBottomInset();
            if (sideBySide(width, height)) {
                double column = columnWidth(width);
                first.resizeRelocate(x, y, column, first.prefHeight(column));
                double secondWidth = Math.min(column, second.maxWidth(-1));
                second.resizeRelocate(x + column + gap(), y, secondWidth, second.prefHeight(secondWidth));
            } else {
                double firstHeight = first.prefHeight(width);
                first.resizeRelocate(x, y, width, firstHeight);
                double secondWidth = snapSizeX(secondWidth(width));
                second.resizeRelocate(x, y + firstHeight + gap(), secondWidth, second.prefHeight(secondWidth));
            }
        }
    }
}
