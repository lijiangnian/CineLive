package com.fongmi.android.tv.source;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Pure policy layer for VOD/live subscription updates; no network or account access. */
public final class SourceMaintenance {
    private SourceMaintenance() {}

    public enum Check { STRUCTURE, DEPENDENCIES, SEARCH, MANIFEST, PLAYBACK }
    public enum Action { ACTIVATE, KEEP_CURRENT, RETRY_LATER, REJECT, NOT_DUE }

    public static final class Snapshot {
        public final String digest;
        public final int entries;
        public final long validatedAt;
        public final Set<Check> checks;

        public Snapshot(String digest, int entries, long validatedAt, Set<Check> checks) {
            if (digest == null || !digest.matches("[0-9a-f]{64}") || entries < 0 || validatedAt < 0)
                throw new IllegalArgumentException("Invalid snapshot metadata");
            this.digest = digest;
            this.entries = entries;
            this.validatedAt = validatedAt;
            this.checks = Collections.unmodifiableSet(checks.isEmpty()
                    ? EnumSet.noneOf(Check.class) : EnumSet.copyOf(checks));
        }
    }

    public static final class Result {
        public final Action action;
        public final String reason;
        public final Snapshot active;
        public final long nextCheckAt;
        public final int consecutiveFailures;

        private Result(Action action, String reason, Snapshot active, long nextCheckAt, int failures) {
            this.action = action;
            this.reason = reason;
            this.active = active;
            this.nextCheckAt = nextCheckAt;
            this.consecutiveFailures = failures;
        }
    }

    /** Returns a decision; caller persists the snapshot transactionally before activation. */
    public static Result evaluate(Snapshot current, Snapshot candidate, Set<Check> required,
                                  long now, long dueAt, long intervalMs, int failures,
                                  long retryAfterMs) {
        if (now < 0 || dueAt < 0 || intervalMs <= 0 || failures < 0 || retryAfterMs < 0)
            throw new IllegalArgumentException("Invalid maintenance timing");
        if (!required.contains(Check.STRUCTURE))
            throw new IllegalArgumentException("Structure validation is mandatory");
        if (now < dueAt) return new Result(Action.NOT_DUE, "cooldown", current, dueAt, failures);
        if (candidate == null)
            return failed(current, now, failures, retryAfterMs, "fetch_or_validation_failed");
        if (candidate.entries == 0 || !candidate.checks.containsAll(required))
            return failed(current, now, failures, retryAfterMs, "required_checks_missing");
        if (candidate.validatedAt > now || (current != null && candidate.validatedAt < current.validatedAt))
            return new Result(Action.REJECT, "stale_or_future_result", current, add(now, intervalMs), failures);
        // A drastic collapse can be a challenge page or partial response. Keep the working snapshot.
        if (current != null && current.entries >= 10 && (long) candidate.entries * 4 < current.entries)
            return failed(current, now, failures, retryAfterMs, "abnormal_entry_drop");
        Action action = current != null && current.digest.equals(candidate.digest)
                ? Action.KEEP_CURRENT : Action.ACTIVATE;
        return new Result(action, action == Action.ACTIVATE ? "validated" : "unchanged_revalidated",
                candidate, add(now, intervalMs), 0);
    }

    private static Result failed(Snapshot current, long now, int failures, long retryAfter, String reason) {
        int count = failures == Integer.MAX_VALUE ? failures : failures + 1;
        long backoff = Math.min(6 * 60 * 60_000L, 60_000L << Math.min(count - 1, 9));
        return new Result(current == null ? Action.RETRY_LATER : Action.KEEP_CURRENT, reason,
                current, add(now, Math.max(backoff, retryAfter)), count);
    }

    private static long add(long now, long delay) {
        return delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
    }
}
