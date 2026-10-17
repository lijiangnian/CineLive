package com.fongmi.android.tv;
import android.content.Context;

import com.fongmi.android.tv.source.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=App.class,shadows={com.fongmi.android.tv.ui.SourceEntryTest.NativePluginLoader.class,UpdateFlowTest.Http.class,UpdateFlowTest.Verifier.class})
public class UpdateFlowTest {
    static String body;static boolean failPrimary,rejectApk;static AtomicInteger secondary=new AtomicInteger();
    @Before public void init() {
        Context app=App.get();
        UpdateRepository.prefs().edit().clear().commit();
        app.getSharedPreferences("private-route",0).edit().clear().commit();
        File dir=new File(app.getFilesDir(),"app-updates");if(dir.listFiles()!=null)for(File f:dir.listFiles())f.delete();
        failPrimary=false;rejectApk=false;secondary.set(0);
        UpdateRepository.configure(UpdateRepository.NAS, "");
    }
    static JSONObject doc() throws Exception {
        String hash="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        return new JSONObject().put("schemaVersion",1).put("packageName",BuildConfig.APPLICATION_ID).put("channel",BuildConfig.UPDATE_CHANNEL)
            .put("versionCode",BuildConfig.VERSION_CODE+1).put("versionName","test-next").put("minSdk",24)
            .put("artifacts",new JSONArray().put(new JSONObject().put("flavor","mobile").put("abi",android.os.Process.is64Bit()?"arm64-v8a":"armeabi-v7a")
                .put("url","http://192.168.31.67:18080/app.apk").put("sha256",hash).put("size",3)));
    }
    @Test @Config(sdk=36) public void updateScreenKeepsSystemBarClearance() {
        try(var controller=org.robolectric.Robolectric.buildActivity(com.fongmi.android.tv.ui.activity.UpdateActivity.class).setup()) {
            android.view.ViewGroup root=controller.get().findViewById(android.R.id.content);
            android.widget.ScrollView scroll=(android.widget.ScrollView)root.getChildAt(0);
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(scroll,new androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.of(0,40,0,32)).build());
            assertEquals(40,scroll.getPaddingTop());assertEquals(32,scroll.getPaddingBottom());assertTrue(scroll.getClipToPadding());
        }
    }
    @Test public void scheduledUpdatesUseUnmeteredNetworkAndCanBeDisabled() {
        UpdateJob.schedule(App.get());
        android.app.job.JobScheduler scheduler=App.get().getSystemService(android.app.job.JobScheduler.class);
        var job=scheduler.getPendingJob(0x435550);assertNotNull(job);
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_UNMETERED,job.getNetworkType());
        UpdateRepository.prefs().edit().putBoolean("automatic",false).commit();UpdateJob.schedule(App.get());
        assertNull(scheduler.getPendingJob(0x435550));
    }
    @Test public void lanHttpOnlyAllowsPrivateLiteralAddresses() {
        for(String s:new String[]{"http://192.168.31.67:18080/manifest.json","http://10.0.0.2/a","https://github.com/a"})UpdateAddress.validate(s);
        for(String s:new String[]{"http://example.com/a","http://127.0.0.1/a","http://192.168.31.67.evil/a","http://192.168.31.67@evil/a","http://172.32.0.1/a"}) {
            try {UpdateAddress.validate(s);fail(s);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void githubProxyIsRestrictedToOwnReleaseFiles() {
        assertTrue(UpdateRepository.githubRelease("https://github.com/lijiangnian/CineLive/releases/latest/download/manifest.json"));
        for(String s:new String[]{"https://github.com/other/CineLive/releases/latest/download/manifest.json","https://github.com/lijiangnian/CineLive/releases/download/../CineLive-a.apk","https://github.com/lijiangnian/CineLive/releases/download/v1/plugin.jar"}) assertFalse(UpdateRepository.githubRelease(s));
    }
    @Test public void wrongDeviceAndDuplicateArtifactsAreRejected() throws Exception {
        JSONObject document=doc();
        try{UpdateRepository.select(document,"leanback","armeabi-v7a",28,0);fail();}catch(IOException expected){}
        document.getJSONArray("artifacts").put(document.getJSONArray("artifacts").get(0));
        try{UpdateRepository.select(document,"mobile",android.os.Process.is64Bit()?"arm64-v8a":"armeabi-v7a",28,0);fail();}catch(IOException expected){}
    }
    @Test public void wrongPackageRejectedAndDowngradeNotOffered() throws Exception {
        JSONObject document=doc().put("packageName","other");
        try{UpdateRepository.select(document,"mobile","armeabi-v7a",28,0);fail();}catch(IOException expected){}
        document=doc().put("versionCode",BuildConfig.VERSION_CODE);
        assertNull(UpdateRepository.select(document,"mobile","armeabi-v7a",28,BuildConfig.VERSION_CODE));
    }
    @Test public void validDownloadIsStagedAndVerifiedBeforeInstall() throws Exception {
        body=doc().toString();new UpdateRepository().check(true);
        assertTrue(UpdateRepository.hasReadyFile());assertEquals(3,UpdateRepository.file(UpdateRepository.ready()).length());
    }
    @Test public void invalidSignerNeverBecomesReady() throws Exception {
        body=doc().toString();rejectApk=true;
        try{new UpdateRepository().check(true);fail();}catch(Exception expected){}
        assertFalse(UpdateRepository.hasReadyFile());
        File dir=new File(App.get().getFilesDir(),"app-updates");for(File file:dir.listFiles())assertFalse(file.getName().endsWith(".part"));
    }
    @Test public void completeTransactionFallsBackWhenPrimaryArtifactFails() throws Exception {
        body=doc().toString();failPrimary=true;
        UpdateRepository.configure(UpdateRepository.NAS,"http://192.168.31.68:18080/manifest.json");
        new UpdateRepository().check(true);assertTrue(UpdateRepository.hasReadyFile());assertTrue(secondary.get()>0);
    }
    @Test public void stoppedTaskDoesNotDownloadOrMarkReady() throws Exception {
        body=doc().toString();UpdateRepository r=new UpdateRepository();r.cancel();
        try{r.check(true);fail();}catch(InterruptedIOException expected){}assertFalse(UpdateRepository.hasReadyFile());
    }
    @Implements(ProductHttp.class) public static class Http {
        @Implementation public static OkHttpClient client(){return new OkHttpClient.Builder().addInterceptor(chain->{
            String host=chain.request().url().host();String path=chain.request().url().encodedPath();
            boolean fallback=host.endsWith("68");if(fallback)secondary.incrementAndGet();
            boolean apk=path.endsWith(".apk");
            String value=apk?"abc":(fallback?body.replace("192.168.31.67","192.168.31.68"):body);
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(failPrimary&&!fallback&&apk?503:200).message("test")
                .body(ResponseBody.create(MediaType.parse("application/octet-stream"),value.getBytes(StandardCharsets.UTF_8))).build();
        }).build();}
    }
    @Implements(ApkVerifier.class) public static class Verifier {
        @Implementation public static void verifyApk(File f,ReleaseArtifact a)throws Exception{if(rejectApk)throw new IOException("wrong signer");}
    }
}
