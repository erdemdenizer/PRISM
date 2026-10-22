package prism.srm.application;

import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Logs each kind of failure once, so that a source failing on every cycle does not write a line
 * per tick. A kind is what failed together with the type of the exception; both come from small
 * fixed sets, which bounds what is remembered. Used by one thread at a time.
 */
final class FailureLog {

    private final Logger log;
    private final Set<String> logged = new HashSet<>();

    FailureLog(Logger log) {
        this.log = log;
    }

    void warnOnce(String what, Throwable failure) {
        if (logged.add(what + '|' + failure.getClass().getName())) {
            log.warn("{} failed; further failures of this kind are not logged", what, failure);
        }
    }
}
