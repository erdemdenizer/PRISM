package prism.srm.platform;

import java.nio.file.Path;

/**
 * Where Prism keeps its files for the current user, by the convention of the running system.
 *
 * <p>An implementation only works out paths: it creates nothing, reads no file, and does not
 * throw. It is used before logging is set up, so it must not use a logger either.
 */
public interface UserFolders {

    /**
     * Returns the folder for log files. It may not exist yet, and it is relative to the working
     * directory when the system gave no absolute home folder to build it from.
     */
    Path logs();
}
