package com.fongmi.android.tv.source;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.EnumSet;

/** Persistent, structure-validated subscriptions. A cached document is NOT a playback guarantee. */
public final class SourceStore {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final long INTERVAL_MS = 6 * 60 * 60_000L;
    private static final int MAGIC = 0x43535331;
    private final File directory;
    // Network writes serialize per subscription. Atomic files keep cached reads independent.
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.locks.ReentrantLock> refreshLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public interface Fetcher { String fetch() throws Exception; }
    public interface Validator { int count(String document) throws Exception; }

    public static final class Unavailable extends IOException {
        public Unavailable() { super("订阅暂不可用，且没有可用缓存"); }
    }

    public static final class HttpFailure extends IOException {
        public final long retryAfterMs;
        public final int status;
        public HttpFailure(int status, long retryAfterMs) {
            super("订阅 HTTP " + status);
            this.status = status;
            this.retryAfterMs = Math.max(0, retryAfterMs);
        }
    }

    public static final class Document {
        public final String text, reason;
        public final long checkedAt, nextCheckAt;
        public final int entries, failures;
        public final boolean fallback;

        private Document(State state) {
            text = state.text;
            reason = state.reason;
            checkedAt = state.checkedAt;
            nextCheckAt = state.nextCheckAt;
            entries = state.entries;
            failures = state.failures;
            fallback = state.failures > 0;
        }
    }

    private static final class State {
        String text = "", reason = "new";
        int entries, failures;
        long checkedAt, nextCheckAt;

        SourceMaintenance.Snapshot snapshot() {
            return text.isEmpty() ? null : new SourceMaintenance.Snapshot(digest(text), entries,
                    checkedAt, EnumSet.of(SourceMaintenance.Check.STRUCTURE));
        }
    }

    public SourceStore(File directory) {
        this.directory = directory;
    }

    /** Includes failure/backoff metadata even before the first successful download. */
    public Document status(String key) throws IOException {
        return new Document(read(key));
    }

    public Document cached(String key) throws IOException {
        State state = read(key);
        if (state.text.isEmpty()) throw new Unavailable();
        return new Document(state);
    }

    /** Force bypasses the normal success interval, never an active failure cooldown. */
    public Document refresh(String key, Fetcher fetcher, Validator validator,
                                         long now, boolean force) throws Exception {
        java.util.concurrent.locks.ReentrantLock lock = refreshLocks.computeIfAbsent(key, ignored -> new java.util.concurrent.locks.ReentrantLock());
        lock.lockInterruptibly();
        try { return refreshLocked(key, fetcher, validator, now, force); }
        finally { lock.unlock(); }
    }

    private Document refreshLocked(String key, Fetcher fetcher, Validator validator,
                                   long now, boolean force) throws Exception {
        checkInterrupted();
        State state = read(key);
        long due = force && state.failures == 0 ? 0 : state.nextCheckAt;
        if (now < due) {
            if (state.text.isEmpty()) throw new Unavailable();
            return new Document(state);
        }
        String candidate = null;
        SourceMaintenance.Snapshot snapshot = null;
        long retryAfter = 0;
        try {
            candidate = fetcher.fetch();
            checkInterrupted();
            if (candidate == null || candidate.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
                throw new IOException("订阅超出大小限制");
            int count = validator.count(candidate);
            checkInterrupted();
            snapshot = new SourceMaintenance.Snapshot(digest(candidate), count, now,
                    EnumSet.of(SourceMaintenance.Check.STRUCTURE));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            checkInterrupted();
            if ("Canceled".equals(e.getMessage())) throw e;
            if (e instanceof HttpFailure) retryAfter = ((HttpFailure) e).retryAfterMs;
            // Never persist exception text: provider errors can contain token-bearing URLs.
        }
        SourceMaintenance.Result result = SourceMaintenance.evaluate(state.snapshot(), snapshot,
                EnumSet.of(SourceMaintenance.Check.STRUCTURE), now, due, INTERVAL_MS,
                state.failures, retryAfter);
        if (snapshot != null && result.active == snapshot) {
            state.text = candidate;
            state.entries = snapshot.entries;
            state.checkedAt = now;
        }
        state.reason = result.reason;
        state.failures = result.consecutiveFailures;
        state.nextCheckAt = result.nextCheckAt;
        checkInterrupted();
        write(key, state);
        if (state.text.isEmpty()) throw new Unavailable();
        return new Document(state);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
    }

    private File file(String key) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("Empty subscription key");
        return new File(directory, digest(key) + ".source");
    }

    private State read(String key) throws IOException {
        File file = file(key);
        if (!file.exists()) return new State();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (file.length() > MAX_BYTES + 1024L || input.readInt() != MAGIC) return new State();
            State state = new State();
            state.checkedAt = input.readLong();
            state.nextCheckAt = input.readLong();
            state.entries = input.readInt();
            state.failures = input.readInt();
            state.reason = input.readUTF();
            String hash = input.readUTF();
            int length = input.readInt();
            if (length < 0 || length > MAX_BYTES || state.checkedAt < 0 || state.nextCheckAt < 0
                    || state.entries < 0 || state.failures < 0) return new State();
            byte[] body = new byte[length];
            input.readFully(body);
            state.text = new String(body, StandardCharsets.UTF_8);
            if (input.read() != -1 || !digest(state.text).equals(hash)
                    || (!state.text.isEmpty() && state.entries == 0)) return new State();
            return state;
        } catch (EOFException | UTFDataFormatException e) {
            return new State();
        }
    }

    private void write(String key, State state) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("无法保存订阅缓存");
        File target = file(key);
        File temp = File.createTempFile("subscription-", ".tmp", directory);
        try {
            try (FileOutputStream stream = new FileOutputStream(temp);
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(stream))) {
                byte[] body = state.text.getBytes(StandardCharsets.UTF_8);
                output.writeInt(MAGIC);
                output.writeLong(state.checkedAt);
                output.writeLong(state.nextCheckAt);
                output.writeInt(state.entries);
                output.writeInt(state.failures);
                output.writeUTF(state.reason);
                output.writeUTF(digest(state.text));
                output.writeInt(body.length);
                output.write(body);
                output.flush();
                stream.getFD().sync();
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
