package prism.srm.platform;

import java.util.Map;
import prism.srm.domain.ProcessIdentity;

// we used map data structure for this file
//this small code fixes the old oshi liblary stuff

public interface ProcessVirtualSizeSource {

    Map<ProcessIdentity, Long> virtualSizes();
}
