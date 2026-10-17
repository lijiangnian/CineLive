package com.fongmi.android.tv.source;

import java.nio.file.Files;

public class ReleaseArtifactCheck {
    public static void main(String[] args) throws Exception {
        String hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        var path = Files.createTempFile("cineshelf-update-check", ".bin");
        try {
            Files.writeString(path, "abc");
            var asset = new ReleaseArtifact("https://example.org/app.apk", hash, 3, 2);
            asset.verifyBytes(path.toFile());
            Files.writeString(path, "abd");
            try { asset.verifyBytes(path.toFile()); throw new AssertionError("tampered file accepted"); }
            catch (IllegalArgumentException expected) { }
            Files.writeString(path, "ab");
            try { asset.verifyBytes(path.toFile()); throw new AssertionError("partial file accepted"); }
            catch (IllegalArgumentException expected) { }
            for (String url : new String[]{"http://example.org/a", "file:///a", "https://user:pass@example.org/a"}) {
                try { new ReleaseArtifact(url, hash, 3, 2); throw new AssertionError("unsafe URL accepted"); }
                catch (IllegalArgumentException expected) { }
            }
            try { new ReleaseArtifact("https://example.org/a", "bad", 3, 2); throw new AssertionError("missing hash accepted"); }
            catch (IllegalArgumentException expected) { }
            try { new ReleaseArtifact("https://example.org/a", hash, 1024L*1024*1024, 2); throw new AssertionError("oversized APK accepted"); }
            catch (IllegalArgumentException expected) { }
            System.out.println("8 release artifact checks passed");
        } finally { Files.deleteIfExists(path); }
    }
}
