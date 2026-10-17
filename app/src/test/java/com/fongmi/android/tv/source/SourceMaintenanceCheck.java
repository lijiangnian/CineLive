package com.fongmi.android.tv.source;

import java.util.EnumSet;
import static com.fongmi.android.tv.source.SourceMaintenance.*;

/** Can run with javac/java independently of Android SDK and external test libraries. */
public class SourceMaintenanceCheck {
    private static int count;
    private static void check(boolean value, String message) {
        count++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var required = EnumSet.of(Check.STRUCTURE, Check.MANIFEST);
        var old = new Snapshot("a".repeat(64), 100, 1000, required);
        var next = new Snapshot("b".repeat(64), 110, 2000, required);
        var r = evaluate(old, next, required, 2000, 0, 60000, 0, 0);
        check(r.action == Action.ACTIVATE && r.active == next, "valid new snapshot activates");
        r = evaluate(old, null, required, 2000, 0, 60000, 0, 0);
        check(r.active == old && r.action == Action.KEEP_CURRENT, "failure retains last known snapshot");
        r = evaluate(old, new Snapshot("b".repeat(64), 100, 2000, EnumSet.of(Check.STRUCTURE)), required, 2000, 0, 60000, 0, 0);
        check(r.active == old, "HTTP/structure alone cannot imply stream validation");
        r = evaluate(old, new Snapshot("b".repeat(64), 2, 2000, required), required, 2000, 0, 60000, 0, 0);
        check(r.active == old && r.reason.equals("abnormal_entry_drop"), "partial update cannot wipe library");
        r = evaluate(null, null, required, 2000, 0, 60000, 0, 120000);
        check(r.nextCheckAt == 122000 && r.active == null, "honor Retry-After without inventing a fallback");
        r = evaluate(old, next, required, 2000, 3000, 60000, 0, 0);
        check(r.action == Action.NOT_DUE, "cooldown enforced");
        r = evaluate(old, new Snapshot("a".repeat(64), 100, 2000, required), required, 2000, 0, 60000, 2, 0);
        check(r.active.validatedAt == 2000 && r.consecutiveFailures == 0, "unchanged valid snapshot refreshes health");
        r = evaluate(old, new Snapshot("b".repeat(64), 100, 500, required), required, 2000, 0, 60000, 0, 0);
        check(r.action == Action.REJECT && r.active == old, "out-of-order result rejected");
        r = evaluate(old, next, required, 2000, 0, Long.MAX_VALUE, 0, 0);
        check(r.nextCheckAt == Long.MAX_VALUE, "schedule overflow bounded");
        try {
            evaluate(old, next, EnumSet.of(Check.PLAYBACK), 2000, 0, 60000, 0, 0);
            throw new AssertionError("structure check must be required");
        } catch (IllegalArgumentException expected) { count++; }
        check(!old.checks.contains(Check.PLAYBACK), "manifest validity is not decoder playback success");
        System.out.println(count + " source maintenance policy checks passed");
    }
}
