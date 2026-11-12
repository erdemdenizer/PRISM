package prism.srm.ui.overview;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The arrangement of the four metrics keeps each graph wider than it is tall where it can. */
class OverviewArrangementTest {

    @Test
    void theUsualWindowShowsTwoByTwo() {
        assertThat(OverviewPage.columns(1040, 520)).isEqualTo(2);
        assertThat(OverviewPage.columns(1650, 880)).isEqualTo(2);
        assertThat(OverviewPage.columns(3170, 1250)).isEqualTo(2);
    }

    @Test
    void aWideLowPageShowsOneRow() {
        assertThat(OverviewPage.columns(1330, 330)).isEqualTo(4);
        assertThat(OverviewPage.columns(1100, 340)).isEqualTo(4);
    }

    @Test
    void oneRowNeedsTheWidthEvenWhereThePageIsLow() {
        assertThat(OverviewPage.columns(1099, 200)).isEqualTo(2);
    }

    /**
     * UI lab v2: in a row of four a tile is at most 520 px wide, the group in the middle of the page; the
     * choice takes the capped tile, so a very wide page that is not low enough keeps two by two.
     */
    @Test
    void aTileInARowOfFourIsAtMostFiveHundredTwentyWide() {
        assertThat(OverviewPage.columns(3300, 650)).isEqualTo(4);
        assertThat(OverviewPage.columns(3300, 651)).isEqualTo(2);
        assertThat(OverviewPage.TILE_MAX).isEqualTo(520);
    }

    /**
     * Fix round H: beside the navigation with names the smallest window leaves the metrics 602 px; one
     * column begins below 560 px (it began below 640 px).
     */
    @Test
    void aNarrowOrTallPageShowsOneColumn() {
        assertThat(OverviewPage.ONE_COLUMN_BELOW).isEqualTo(560);
        assertThat(OverviewPage.columns(559, 300)).isEqualTo(1);
        assertThat(OverviewPage.columns(560, 300)).isEqualTo(2);
        assertThat(OverviewPage.columns(840, 1050)).isEqualTo(1);
    }

    @Test
    void aPageAsWideAsItIsTallStaysTwoByTwo() {
        assertThat(OverviewPage.columns(700, 600)).isEqualTo(2);
        assertThat(OverviewPage.columns(700, 700)).isEqualTo(2);
        assertThat(OverviewPage.columns(700, 701)).isEqualTo(1);
    }
}
