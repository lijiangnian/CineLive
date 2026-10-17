package com.fongmi.android.tv.source;

import java.io.*;
import java.nio.file.Files;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class SourceStoreCheck {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("source-store-").toFile();
        try {
            SourceStore store = new SourceStore(directory);
            check(store.status("new:untested").checkedAt == 0 && store.status("new:untested").failures == 0,
                    "unconfigured status is distinguishable from a failed check");
            AtomicInteger requests = new AtomicInteger();
            SourceStore.Fetcher online = () -> { requests.incrementAndGet(); return "working"; };
            SourceStore.Validator validator = text -> text.equals("working") ? 20 : 1;
            SourceStore.Document first = store.refresh("vod:one", online, validator, 1000, false);
            check(first.text.equals("working") && first.entries == 20 && !first.fallback, "first fetch");
            store = new SourceStore(directory);
            check(store.cached("vod:one").text.equals("working"), "survives process restart");
            store.refresh("vod:one", online, validator, 2000, false);
            check(requests.get() == 1, "success interval avoids network");
            SourceStore.Document failed = store.refresh("vod:one", () -> { throw new SocketTimeoutException(); },
                    validator, 3000, true);
            check(failed.fallback && failed.text.equals("working"), "timeout keeps cache");
            store = new SourceStore(directory);
            store.refresh("vod:one", online, validator, 4000, true);
            check(requests.get() == 1, "manual refresh respects persisted failure cooldown");
            SourceStore.Document recovered = store.refresh("vod:one", online, validator, failed.nextCheckAt, false);
            check(!recovered.fallback && recovered.failures == 0, "recovery clears failures");
            SourceStore.Document drop = store.refresh("vod:one", () -> "partial", validator, 100_000, true);
            check(drop.text.equals("working") && drop.reason.equals("abnormal_entry_drop"), "partial response retained");
            SourceStore.Document empty = store.refresh("vod:one", () -> "", text -> 0, drop.nextCheckAt, false);
            check(empty.text.equals("working") && empty.fallback, "empty response retained");
            long now = empty.nextCheckAt;
            SourceStore.Document limited = store.refresh("vod:one", () -> { throw new SourceStore.HttpFailure(429, 600_000); },
                    validator, now, false);
            check(limited.nextCheckAt >= now + 600_000, "Retry-After honored");
            SourceStore.Document renewed = store.refresh("vod:one", () -> "new", text -> 18, limited.nextCheckAt, false);
            check(renewed.text.equals("new") && renewed.entries == 18, "new valid document activates");
            try {
                store.refresh("vod:one", () -> { throw new InterruptedException(); }, validator,
                        renewed.nextCheckAt, false);
                throw new AssertionError("cancellation swallowed");
            } catch (InterruptedException expected) {
                check(Thread.currentThread().isInterrupted(), "cancellation propagated");
                Thread.interrupted();
            }
            check(store.cached("vod:one").failures == 0, "cancellation is not provider failure");
            try { store.cached("live:one"); throw new AssertionError("keys mixed"); }
            catch (SourceStore.Unavailable expected) { check(true, "VOD and live isolated"); }
            SourceStore.Document oversize = store.refresh("vod:one", () -> "x".repeat(SourceStore.MAX_BYTES + 1),
                    validator, renewed.nextCheckAt, false);
            check(oversize.text.equals("new") && oversize.fallback, "oversize rejected");
            try { store.refresh("new:key", () -> "bad", text -> { throw new IOException(); }, 1000, false);
                throw new AssertionError("invalid initial response accepted"); }
            catch (SourceStore.Unavailable expected) { check(true, "initial failure reports unavailable"); }
            SourceStore.Document initialFailure = new SourceStore(directory).status("new:key");
            check(initialFailure.text.isEmpty() && initialFailure.failures == 1 && initialFailure.nextCheckAt > 1000,
                    "first failure exposes durable retry status without pretending to have a cache");
            for (File file : directory.listFiles()) Files.write(file.toPath(), new byte[]{0, 1});
            try { store.cached("vod:one"); throw new AssertionError("corrupt cache accepted"); }
            catch (SourceStore.Unavailable expected) { check(true, "truncated cache discarded"); }
            check(store.refresh("vod:one", online, validator, 2000, false).text.equals("working"), "corruption refetched");
            check(directory.listFiles((dir, name) -> name.endsWith(".tmp")).length == 0, "no partial writes left");
            System.out.println("SourceStore: " + checks + " checks passed");
        } finally {
            for (File file : directory.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
