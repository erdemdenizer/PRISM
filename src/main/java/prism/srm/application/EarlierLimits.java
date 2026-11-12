package prism.srm.application;

import java.util.Optional;
import prism.srm.domain.ProcessIdentity;

/**
 * Whether a native limit of an earlier (or another) Prism session may still cap a process, as far
 * as recovery knows (milestone 10, external audit item 2): the emulated CPU limit is neither offered
 * nor started over such a cap. Answers from what recovery has already read; never reads a file, so
 * the limiter thread can ask.
 */
public interface EarlierLimits {

    /** Where no ledger of another session can exist: nothing blocks. */
    EarlierLimits NONE = new EarlierLimits() {
        @Override
        public Optional<String> mayStillCap(ProcessIdentity identity) {
            return Optional.empty();
        }

        @Override
        public Optional<String> pending() {
            return Optional.empty();
        }
    };

    /** Why the process may still be capped natively by another session; empty when it cannot be. */
    Optional<String> mayStillCap(ProcessIdentity identity);

    /** What recovery has not settled yet, for the Limits page; empty when everything is settled. */
    Optional<String> pending();
}
