package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.Breakpoints;
import prism.srm.ui.Typefaces;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.icon.Icon;
import prism.srm.ui.icon.IconView;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * UI lab, Phase C: the 60 px icon rail of the shell against a top navigation bar, at 720 x 480, 1100 x
 * 700 and 1600 x 500 (lab-docs/NAV_COMPARISON.md). The top bar exists only here: it is built around the
 * real shell, whose rail is taken away, so both variants show the same page with the same stylesheet.
 *
 * <p>Besides the PNGs it writes {@code nav-measure.txt}: the width of each destination's name in Plex
 * 13.5 Medm, what one top-bar entry needs, and how many entries fit at each width.
 *
 * <p>A research probe, not a test of behaviour: the class name keeps it out of every build. Run it with
 * {@code ./mvnw -q -Pui-snapshots test -Dtest=NavComparisonProbe}; it needs a display.
 */
@Tag("ui-snapshot")
class NavComparisonProbe {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    /** The top bar: 44 px high, 16 px margins, entries of 12 + icon 16 + 8 + name + 12, 2 px apart. */
    private static final double BAR_HEIGHT = 44;

    private static final double MARGIN = 16;
    private static final double ENTRY_PADDING = 12;
    private static final double ICON = 16;
    private static final double GAP = 8;
    private static final double SPACING = 2;
    /** The overflow entry: a 32 px square. */
    private static final double MORE = 32;

    private static final int[][] SIZES = {{720, 480}, {1100, 700}, {1600, 500}};

    /** Plex 13.5 Medm, the face and size of the entries. Measured on the JavaFX thread by the harness. */
    private static final String TOP_CSS = """
            .topnav { -fx-background-color: -srm-chrome; -fx-border-color: transparent transparent -srm-hairline transparent;
                      -fx-border-width: 0 0 1 0; -fx-padding: 0 16 0 16; -fx-spacing: 2; -fx-alignment: center-left; }
            .topnav-item { -fx-background-color: transparent; -fx-background-radius: 6; -fx-padding: 0 12 0 12;
                      -fx-min-height: 32; -fx-pref-height: 32; -fx-graphic-text-gap: 8; -fx-text-fill: -srm-text-muted;
                      -fx-font-family: "IBM Plex Sans Medm"; -fx-font-size: 13.5px; -srm-icon: -srm-text-muted; }
            .topnav-item:selected { -fx-background-color: -srm-accent, -srm-selection; -fx-background-insets: 30 10 0 10, 0 0 2 0;
                      -fx-text-fill: -srm-text; -srm-icon: -srm-accent; }
            .shell .nav-toggle { visibility: hidden; }
            .shell:narrow .page .page-header { -fx-padding: 0; }
            """;

    @Test
    void rendersTheRailAndATopBarAtThreeSizesAndRecordsTheMeasures() throws IOException {
        List<String> report = new ArrayList<>();
        double[] names = SnapshotHarness.inspect(Theme.DARK, 400, 300, () -> new HBox(), root -> {
            double[] widths = new double[Destination.values().length];
            Font font = Font.font(Typefaces.MEDIUM_FAMILY, 13.5);
            for (Destination destination : Destination.values()) {
                Text text = new Text(destination.title());
                text.setFont(font);
                widths[destination.ordinal()] = Math.ceil(text.getLayoutBounds().getWidth());
            }
            return widths;
        });
        double total = 0;
        report.add("Name widths, IBM Plex Sans Medm 13.5 px (ceil):");
        for (Destination destination : Destination.values()) {
            double entry = entryWidth(names[destination.ordinal()]);
            total += entry + SPACING;
            report.add(String.format(
                    Locale.ROOT,
                    "  %-12s name %3.0f  entry %3.0f",
                    destination.title(),
                    names[destination.ordinal()],
                    entry));
        }
        total -= SPACING;
        report.add(String.format(Locale.ROOT, "All seven entries in a top bar: %.0f px", total));
        report.add("");
        for (int[] size : SIZES) {
            double available = size[0] - 2 * MARGIN;
            int fit = fitting(names, available);
            report.add(String.format(
                    Locale.ROOT,
                    "%dx%d  top bar: available %.0f px, entries that fit %d of 7%s; vertical cost %.0f px (%.1f %% of the height)",
                    size[0],
                    size[1],
                    available,
                    fit,
                    fit < 7 ? " (+ More)" : "",
                    BAR_HEIGHT,
                    100 * BAR_HEIGHT / size[1]));
            report.add(String.format(
                    Locale.ROOT,
                    "%dx%d  rail: horizontal cost %s; vertical cost 0 px",
                    size[0],
                    size[1],
                    String.format(
                            Locale.ROOT,
                            "%.0f px (%.1f %% of the width)",
                            Breakpoints.RAIL_WIDTH,
                            100 * Breakpoints.RAIL_WIDTH / size[0])));

            for (Theme theme : Theme.values()) {
                Path rail = SnapshotHarness.render("nav-rail", theme, size[0], size[1], NavComparisonProbe::shell);
                Path top =
                        SnapshotHarness.render("nav-top", theme, size[0], size[1], () -> topVariant(names, available));
                assertThat(rail).isRegularFile().isNotEmptyFile();
                assertThat(top).isRegularFile().isNotEmptyFile();
            }
        }
        Path out = Path.of(System.getProperty("srm.snapshot.dir", "target/ui-snapshots"), "nav-measure.txt");
        try {
            Files.write(out, report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(out).isRegularFile();
    }

    private static double entryWidth(double name) {
        return ENTRY_PADDING + ICON + GAP + name + ENTRY_PADDING;
    }

    /** How many entries fit in order, leaving room for the overflow button when not all do. */
    private static int fitting(double[] names, double available) {
        double used = 0;
        int fit = 0;
        for (int index = 0; index < names.length; index++) {
            double next = used + (index == 0 ? 0 : SPACING) + entryWidth(names[index]);
            boolean last = index == names.length - 1;
            double reserve = last ? 0 : SPACING + MORE;
            if (next + reserve > available) {
                break;
            }
            used = next;
            fit++;
        }
        return fit;
    }

    private static Shell shell() {
        Shell shell = new Shell(
                new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, identity -> {});
        shell.showSnapshot(Snapshots.live());
        return shell;
    }

    private static Parent topVariant(double[] names, double available) {
        Shell shell = shell();
        shell.setLeft(null);
        HBox bar = new HBox();
        bar.getStyleClass().add("topnav");
        bar.setMinHeight(BAR_HEIGHT);
        bar.setPrefHeight(BAR_HEIGHT);
        bar.setMaxHeight(BAR_HEIGHT);
        bar.setAlignment(Pos.CENTER_LEFT);
        ToggleGroup group = new ToggleGroup();
        int fit = fitting(names, available);
        Destination[] all = Destination.values();
        for (int index = 0; index < fit; index++) {
            ToggleButton entry = new ToggleButton(all[index].title(), new IconView(all[index].icon(), ICON));
            entry.getStyleClass().setAll("topnav-item");
            entry.setContentDisplay(ContentDisplay.LEFT);
            entry.setToggleGroup(group);
            entry.setSelected(index == 0);
            bar.getChildren().add(entry);
        }
        if (fit < all.length) {
            MenuButton more = new MenuButton(null, new IconView(Icon.ELLIPSIS, ICON));
            more.getStyleClass().add("quiet");
            more.setAccessibleText("More pages");
            for (int index = fit; index < all.length; index++) {
                more.getItems().add(new MenuItem(all[index].title()));
            }
            bar.getChildren().add(more);
        }
        BorderPane root = new BorderPane(shell);
        root.setTop(bar);
        root.getStylesheets()
                .add("data:text/css;base64,"
                        + Base64.getEncoder().encodeToString(TOP_CSS.getBytes(StandardCharsets.UTF_8)));
        return root;
    }
}
