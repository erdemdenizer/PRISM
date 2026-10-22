package prism.srm.platform.oshi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import prism.srm.domain.DeviceLayer;

class DiskLayerCacheTest {

    private final Map<String, DeviceLayer> systemAnswer = new HashMap<>(Map.of("disk0", DeviceLayer.PHYSICAL));
    private int queries;
    private boolean queryFails;

    private final DiskLayerCache cache = new DiskLayerCache(() -> {
        if (queryFails) {
            throw new IllegalStateException("query failed");
        }
        queries++;
        return systemAnswer;
    });

    @Test
    void theSystemIsAskedOnceWhileTheDisksStayTheSame() {
        assertThat(cache.layersFor(Set.of("disk0#1"))).containsOnly(Map.entry("disk0", DeviceLayer.PHYSICAL));
        cache.layersFor(Set.of("disk0#1"));
        cache.layersFor(Set.of("disk0#1"));

        assertThat(queries).isEqualTo(1);
    }

    @Test
    void theSystemIsAskedAgainWhenADiskAppearsGoesOrIsReplaced() {
        cache.layersFor(Set.of("disk0#1"));
        systemAnswer.put("disk1", DeviceLayer.LOGICAL);

        assertThat(cache.layersFor(Set.of("disk0#1", "disk1#2")))
                .containsOnly(Map.entry("disk0", DeviceLayer.PHYSICAL), Map.entry("disk1", DeviceLayer.LOGICAL));

        cache.layersFor(Set.of("disk0#1"));
        cache.layersFor(Set.of("disk0#3"));

        assertThat(queries).isEqualTo(4);
    }

    @Test
    void anAnswerIsNotAffectedByLaterChangesToTheMapTheSourceReturned() {
        Map<String, DeviceLayer> layers = cache.layersFor(Set.of("disk0#1"));

        systemAnswer.put("disk0", DeviceLayer.LOGICAL);

        assertThat(layers).containsOnly(Map.entry("disk0", DeviceLayer.PHYSICAL));
    }

    @Test
    void aFailedQueryIsRepeatedOnTheNextCallInsteadOfLeavingAStaleAnswer() {
        cache.layersFor(Set.of("disk0#1"));
        queryFails = true;

        assertThatIllegalStateException().isThrownBy(() -> cache.layersFor(Set.of("disk0#1", "disk1#2")));

        queryFails = false;
        systemAnswer.put("disk1", DeviceLayer.LOGICAL);

        assertThat(cache.layersFor(Set.of("disk0#1", "disk1#2"))).containsKey("disk1");
        assertThat(queries).isEqualTo(2);
    }
}
