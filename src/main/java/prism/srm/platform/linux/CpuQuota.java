package prism.srm.platform.linux;

import java.util.OptionalLong;

/**
 * The conversion between Prism's CPU limit and the cgroup v2 / systemd quota (milestone 12, LIM-6, plan Rev 1 §3.8).
 *
 * <p>Prism's limit is a whole percentage P (1 to 100) of the whole machine with N logical processors, the unit of
 * PROC-2. systemd's {@code CPUQuotaPerSecUSec} is processor time per second of wall time, so P % of N processors is
 * {@code P x N x 10 000} microseconds per second; with systemd's default period of 100 ms the kernel shows it as
 * {@code cpu.max = "P x N x 1000 100000"}. Every product is computed exactly; an input that would overflow, or a
 * processor count outside what Prism measures, is refused. The two conversions below read a limit of 100 % as no
 * quota, {@code max}; only tests call them. The limiter sets 100 % explicitly as the whole machine (ADR-0016 decision
 * 3), so that its read-back is an exact rate.
 *
 * <p>Back from a read-back: a {@code cpu.max} quota Q per period T over N processors is the share {@code Q / (T x N)}
 * of the machine, which in Prism's unit (hundredths of a percent, {@link prism.srm.domain.EffectiveLimits#cpuRate})
 * is {@code Q x 10 000 / (T x N)}. Only an exact whole rate from 1 to 10 000 is Prism's; anything else is foreign.
 */
final class CpuQuota {

    /** systemd's default CPUQuotaPeriodSec, 100 ms (systemd.resource-control(5)). */
    static final long PERIOD_USEC = 100_000;

    /** The most logical processors Prism converts for: far above any machine it runs on, far below an overflow. */
    static final int MOST_PROCESSORS = 1 << 16;

    /** systemd's "infinity" for a uint64 property such as CPUQuotaPerSecUSec: UINT64_MAX, -1 as a signed long. */
    static final long INFINITY = -1L;

    private CpuQuota() {}

    /** What a read-back quota is in Prism's terms. */
    enum Kind {
        /** No quota at all. */
        UNLIMITED,
        /** Exactly a whole rate Prism can have set. */
        EXACT,
        /** A quota Prism never sets: not a whole rate of hundredths of a percent, or above the whole machine. */
        FOREIGN
    }

    /** A read-back quota: its kind, and for {@link Kind#EXACT} the rate in hundredths of a percent (1 to 10 000). */
    record Share(Kind kind, int rate) {

        static final Share UNLIMITED = new Share(Kind.UNLIMITED, 0);
        static final Share FOREIGN = new Share(Kind.FOREIGN, 0);

        Share {
            if ((kind == Kind.EXACT) != (rate >= 1 && rate <= 10_000)) {
                throw new IllegalArgumentException("A rate belongs to an exact share only: " + kind + " " + rate);
            }
        }
    }

    /**
     * {@code CPUQuotaPerSecUSec} for a limit of P % of N processors; empty for 100 % (no quota, systemd's infinity).
     *
     * @throws IllegalArgumentException for a percentage outside 1..100 or a processor count outside 1..{@link
     *     #MOST_PROCESSORS}
     */
    static OptionalLong quotaPerSecondUsec(int percent, int processors) {
        check(percent, processors);
        if (percent == 100) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.multiplyExact(Math.multiplyExact((long) percent, processors), 10_000L));
    }

    /** The {@code cpu.max} text the kernel shows for a limit of P % of N processors at the default period. */
    static String cpuMaxText(int percent, int processors) {
        check(percent, processors);
        if (percent == 100) {
            return "max " + PERIOD_USEC;
        }
        long quota = Math.multiplyExact(Math.multiplyExact((long) percent, processors), 1_000L);
        return quota + " " + PERIOD_USEC;
    }

    /** What a {@code cpu.max} read-back is over N processors. */
    static Share share(CgroupV2.CpuMax max, int processors) {
        if (processors < 1 || processors > MOST_PROCESSORS) {
            throw new IllegalArgumentException("Not a processor count: " + processors);
        }
        if (max.unlimited()) {
            return Share.UNLIMITED;
        }
        return exact(max.quotaUsec().getAsLong(), Math.multiplyExact(max.periodUsec(), (long) processors));
    }

    /** What a {@code CPUQuotaPerSecUSec} read-back is over N processors ({@link #INFINITY} for no quota). */
    static Share shareOfQuotaPerSecond(long usecPerSecond, int processors) {
        if (processors < 1 || processors > MOST_PROCESSORS) {
            throw new IllegalArgumentException("Not a processor count: " + processors);
        }
        if (usecPerSecond == INFINITY) {
            return Share.UNLIMITED;
        }
        if (usecPerSecond <= 0) {
            return Share.FOREIGN;
        }
        return exact(usecPerSecond, Math.multiplyExact(1_000_000L, (long) processors));
    }

    /** {@code quota x 10 000 / whole}, when it is an exact rate from 1 to 10 000. */
    private static Share exact(long quota, long whole) {
        if (quota <= 0 || whole <= 0) {
            return Share.FOREIGN;
        }
        long scaled;
        try {
            scaled = Math.multiplyExact(quota, 10_000L);
        } catch (ArithmeticException overflow) {
            return Share.FOREIGN;
        }
        if (scaled % whole != 0) {
            return Share.FOREIGN;
        }
        long rate = scaled / whole;
        return rate >= 1 && rate <= 10_000 ? new Share(Kind.EXACT, (int) rate) : Share.FOREIGN;
    }

    private static void check(int percent, int processors) {
        if (percent < 1 || percent > 100) {
            throw new IllegalArgumentException("A CPU limit is a whole percentage from 1 to 100: " + percent);
        }
        if (processors < 1 || processors > MOST_PROCESSORS) {
            throw new IllegalArgumentException("Not a processor count: " + processors);
        }
    }
}
