package com.fongmi.android.tv.source;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Locale;

/** A selected release asset; Android additionally verifies package, version and APK signer. */
public final class ReleaseArtifact {
    public final String url;
    public final String sha256;
    public final long size;
    public final long versionCode;

    public ReleaseArtifact(String url, String sha256, long size, long versionCode) {
        this(url, sha256, size, versionCode, false);
    }

    public ReleaseArtifact(String url, String sha256, long size, long versionCode, boolean allowLan) {
        URI uri = URI.create(url);
        UpdateAddress.validate(url);
        if (!allowLan && !"https".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("HTTPS update URL required");
        if (sha256 == null || !sha256.matches("(?i)[a-f0-9]{64}"))
            throw new IllegalArgumentException("SHA-256 is required");
        if (size <= 0 || size > 512L * 1024 * 1024 || versionCode <= 0)
            throw new IllegalArgumentException("Invalid APK metadata");
        this.url = url;
        this.sha256 = sha256.toLowerCase(Locale.ROOT);
        this.size = size;
        this.versionCode = versionCode;
    }

    public void verifyBytes(File file) throws Exception {
        if (!file.isFile() || file.length() != size) throw new IllegalArgumentException("APK size mismatch");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        if (!actual.toString().equals(sha256)) throw new IllegalArgumentException("APK checksum mismatch");
    }
}
