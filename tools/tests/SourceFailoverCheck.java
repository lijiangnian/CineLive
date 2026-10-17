package com.fongmi.android.tv.source;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Real local HTTP responses drive the same selector/cache used by scheduled maintenance. */
public final class SourceFailoverCheck {
    private static int checks;
    private static void require(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/broken", e -> { e.sendResponseHeaders(503, -1); e.close(); });
        for (String body : List.of("0", "1", "20", "<html>challenge</html>")) {
            String path = body.startsWith("<") ? "/html" : "/" + body;
            server.createContext(path, e -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(200, bytes.length);
                e.getResponseBody().write(bytes); e.close();
            });
        }
        server.start();
        var directory = Files.createTempDirectory("stream-failover-");
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            AtomicInteger calls = new AtomicInteger();
            SourceFailover.Fetcher http = address -> {
                calls.incrementAndGet();
                HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
                c.setConnectTimeout(2000); c.setReadTimeout(2000);
                try {
                    if (c.getResponseCode() != 200) throw new IOException("HTTP failure");
                    try (var in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                } finally { c.disconnect(); }
            };
            SourceStore.Validator validator = Integer::parseInt;
            var result = SourceFailover.fetch(List.of(origin+"/broken", origin+"/20"), http, validator, 20);
            require(result.addressIndex == 1 && result.text.equals("20"), "HTTP failure selects mirror");
            result = SourceFailover.fetch(List.of(origin+"/html", origin+"/0", origin+"/1", origin+"/20"), http, validator, 20);
            require(result.addressIndex == 3, "reject challenge, empty and collapsed lists");
            calls.set(0);
            result = SourceFailover.fetch(List.of(origin+"/20", origin+"/broken"), http, validator, 20);
            require(result.addressIndex == 0 && calls.get() == 1, "healthy primary short circuits");
            SourceStore store = new SourceStore(directory.toFile());
            long now = 1_000_000;
            var initial = store.refresh("VOD:primary", () -> "20", validator, now, true);
            var fallback = store.refresh("VOD:primary", () -> SourceFailover.fetch(
                    List.of(origin+"/broken", origin+"/html"), http, validator, 20).text, validator, now+1, true);
            require(fallback.fallback && fallback.text.equals(initial.text), "all failed keeps active snapshot");
            SourceStore restarted = new SourceStore(directory.toFile());
            require(restarted.cached("VOD:primary").text.equals("20"), "cache survives restart");
            calls.set(0);
            restarted.refresh("VOD:primary", () -> { calls.incrementAndGet(); return "20"; }, validator, now+2, true);
            require(calls.get() == 0, "manual refresh respects failure cooldown");
            var recovered = restarted.refresh("VOD:primary", () -> SourceFailover.fetch(
                    List.of(origin+"/broken", origin+"/20"), http, validator, 20).text, validator, fallback.nextCheckAt, false);
            require(!recovered.fallback && recovered.failures == 0, "automatic recovery clears failure state");
            require(restarted.cached("VOD:primary").entries == 20, "recovered snapshot committed");
            try { restarted.cached("LIVE:primary"); throw new AssertionError("cross-kind cache leak"); }
            catch (SourceStore.Unavailable expected) { checks++; }
            AtomicInteger attempts = new AtomicInteger();
            try {
                SourceFailover.fetch(List.of("a", "b"), address -> { attempts.incrementAndGet(); throw new InterruptedIOException("Canceled"); }, validator, 0);
                throw new AssertionError("cancellation swallowed");
            } catch (InterruptedIOException expected) { require(attempts.get() == 1, "cancel never tries mirrors"); }
            try {
                SourceFailover.fetch(List.of("a", "b"), address -> { throw new SourceStore.HttpFailure(429, address.equals("a")?1000:5000); }, validator, 0);
                throw new AssertionError("rate limit swallowed");
            } catch (SourceStore.HttpFailure expected) { require(expected.retryAfterMs == 5000, "honor longest retry-after"); }
            calls.set(0);
            Thread.currentThread().interrupt();
            try { SourceFailover.fetch(List.of(origin+"/20"), http, validator, 0); throw new AssertionError("interrupt ignored"); }
            catch (InterruptedIOException expected) { require(calls.get()==0, "interrupted job makes no request"); }
            finally { Thread.interrupted(); }
            System.out.println("Source failover integration checks: " + checks + " passed");
        } finally {
            server.stop(0);
            try (var paths = Files.walk(directory)) {
                for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
