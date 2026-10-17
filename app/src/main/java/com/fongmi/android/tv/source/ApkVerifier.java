package com.fongmi.android.tv.source;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import android.os.Build;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.File;
import java.util.Set;
import java.util.HashSet;
public final class ApkVerifier {
    public static void verifyApk(File file, ReleaseArtifact artifact) throws Exception {
        PackageManager manager = App.get().getBaseContext().getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo update = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags | PackageManager.GET_META_DATA);
        PackageInfo installed = manager.getPackageInfo(BuildConfig.APPLICATION_ID, flags);
        if (update == null || !BuildConfig.APPLICATION_ID.equals(update.packageName)) throw new IllegalArgumentException("Wrong APK package");
        if (update.applicationInfo == null || update.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT
                || update.applicationInfo.metaData == null
                || !BuildConfig.FLAVOR.equals(update.applicationInfo.metaData.getString("dev.cineshelf.device")))
            throw new IllegalArgumentException("Wrong APK device or Android version");
        long code = Build.VERSION.SDK_INT >= 28 ? update.getLongVersionCode() : update.versionCode;
        if (code != artifact.versionCode || code <= BuildConfig.VERSION_CODE) throw new IllegalArgumentException("Wrong APK version");
        Set<String> expected = signers(installed);
        if (expected.isEmpty() || !expected.equals(signers(update))) throw new IllegalArgumentException("APK signer mismatch");
    }

    private static Set<String> signers(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures) result.add(signature.toCharsString());
        return result;
    }
}
