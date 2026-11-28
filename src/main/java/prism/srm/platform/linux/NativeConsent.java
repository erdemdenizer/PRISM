package prism.srm.platform.linux;

import java.util.List;
import java.util.Set;
import prism.srm.domain.ProcessIdentity;

/**
 * The consent tokens a Linux native limit needs (milestone 12, plan Rev 2.2 §2.6, §3, K16, K18): a per-process consent
 * to adoption, which is one-way, and a separate consent for a hard memory limit, under which the kernel may terminate
 * the process or a child. The limiter refuses an adoption without the first and a hard memory limit without the second,
 * before it asks anything of the system.
 *
 * <p>These are the consents a limiter is made with. The product's limiter is made with none ({@link #none()}); a user
 * gives them per request instead, in the consent dialog, which the limiter asks for with a {@code CONSENT_REQUIRED}
 * answer that carries the statements below ({@code NativeConsentOffer}), and admits for that call only. Under K16 no
 * process reaches that dialog: desktop applications are not eligible at all. Only a test harness declares consents at
 * construction, for the disposable processes it created ({@link #harness}). The texts the dialog shows are fixed here,
 * so that the consent the user gives and the one the harness declares stand for exactly these statements.
 */
final class NativeConsent {

    /** The statements a user reads before a first adoption (plan §2.6, items 1 to 5). */
    static final List<String> ADOPTION_STATEMENTS = List.of(
            "This process will be moved into a systemd scope that PRISM creates for it.",
            "When the limits are removed, the process does not return to its earlier systemd unit; it stays in this scope"
                    + " until it exits.",
            "Processes it starts from now on share the same limits and stay in the same scope.",
            "Your desktop environment or service manager may see this application's ownership or lifecycle differently.",
            "Anyone who stops this scope from outside, for example with systemctl --user stop, terminates the processes"
                    + " in it; PRISM never does.");

    /** The statement a user confirms, separately, before a hard memory limit (plan §3). */
    static final String HARD_MEMORY_STATEMENT =
            "The Linux kernel may terminate this process or one of its child processes when it reaches the limit.";

    private final Set<ProcessIdentity> adoption;
    private final Set<ProcessIdentity> hardMemory;

    private NativeConsent(Set<ProcessIdentity> adoption, Set<ProcessIdentity> hardMemory) {
        this.adoption = Set.copyOf(adoption);
        this.hardMemory = Set.copyOf(hardMemory);
        if (!this.adoption.containsAll(this.hardMemory)) {
            throw new IllegalArgumentException("A hard memory consent presupposes the adoption consent");
        }
    }

    /** The product in milestone 12: no consent can be given. */
    static NativeConsent none() {
        return new NativeConsent(Set.of(), Set.of());
    }

    /** A test harness's explicit declaration for the disposable processes it created. */
    static NativeConsent harness(Set<ProcessIdentity> adoption, Set<ProcessIdentity> hardMemory) {
        return new NativeConsent(adoption, hardMemory);
    }

    boolean adoption(ProcessIdentity identity) {
        return adoption.contains(identity);
    }

    boolean hardMemory(ProcessIdentity identity) {
        return hardMemory.contains(identity);
    }
}
