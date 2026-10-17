package com.fongmi.android.tv.source;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Fetches a subscription from its publisher's ordered addresses, never runs a plugin. */
public final class SourceFailover {
    public interface Fetcher { String fetch(String address) throws Exception; }
    public static final class Candidate {
        public final String text;
        public final int addressIndex;
        Candidate(String text, int index) { this.text = text; addressIndex = index; }
    }

    public static Candidate fetch(List<String> addresses, Fetcher fetcher,
                                  SourceStore.Validator validator, int previousEntries) throws Exception {
        if (addresses.isEmpty() || addresses.size() > 6)
            throw new IllegalArgumentException("订阅地址数量无效");
        long retryAfter = 0;
        for (int i = 0; i < addresses.size(); i++) {
            interrupted();
            try {
                String text = fetcher.fetch(addresses.get(i));
                interrupted();
                if (text == null || text.getBytes(StandardCharsets.UTF_8).length > SourceStore.MAX_BYTES)
                    throw new IOException("订阅内容无效");
                int count = validator.count(text);
                if (count <= 0 || previousEntries >= 10 && (long) count * 4 < previousEntries)
                    throw new IOException("订阅条目异常减少");
                return new Candidate(text, i);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                interrupted();
                if ("Canceled".equals(e.getMessage())) throw e;
                if (e instanceof SourceStore.HttpFailure)
                    retryAfter = Math.max(retryAfter, ((SourceStore.HttpFailure) e).retryAfterMs);
            }
        }
        // Do not expose addresses or provider error bodies, which may contain credentials.
        if (retryAfter > 0) throw new SourceStore.HttpFailure(429, retryAfter);
        throw new IOException("所有订阅地址暂不可用");
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
    }
    private SourceFailover() {}
}
