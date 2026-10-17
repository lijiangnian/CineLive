package com.fongmi.android.tv.source;

import android.content.SharedPreferences;
import android.os.Build;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One transaction at a time across manual and scheduled checks. No source-maintenance proxy. */
public final class UpdateRepository {
    public static final String GITHUB = "https://github.com/lijiangnian/CineLive/releases/latest/download/manifest.json";
    public static final String NAS = "http://192.168.31.67:18080/manifest.json";
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private volatile Call call;
    private volatile boolean cancelled;
    private volatile maintenancecore.UpdateDownload tunnel;
    private volatile File receiving;
    private volatile long expected;
    private volatile String stage="正在连接更新服务…";
    public String progress() { File f=receiving; return f!=null&&expected>0 ? stage+" "+Math.min(100,f.length()*100/expected)+"%" : stage; }
    public static SharedPreferences prefs() { return App.get().getSharedPreferences("app-updates",0); }
    public static String address() { return prefs().getString("url", BuildConfig.UPDATE_MANIFEST_URL.isEmpty() ? GITHUB : BuildConfig.UPDATE_MANIFEST_URL); }
    public static boolean automatic() { return prefs().getBoolean("automatic", true); }
    public static String status() { return prefs().getString("status", "尚未检查。请先在极空间部署更新服务。"); }
    public static void configure(String url, String backup) {
        UpdateAddress.validate(url);
        if (!backup.isEmpty()) UpdateAddress.validate(backup);
        prefs().edit().putString("url",url).putString("backup",backup).putLong("checked",0).apply();
    }
    private static File directory() {
        File dir=new File(App.get().getFilesDir(),"app-updates"); dir.mkdirs(); return dir;
    }
    public static File file(ReleaseArtifact artifact) { return new File(directory(),artifact.sha256+".apk"); }
    public static ReleaseArtifact select(JSONObject document, String flavor, String abi, int sdk, long installed) throws Exception {
        if (document.getInt("schemaVersion")!=1 || !BuildConfig.APPLICATION_ID.equals(document.getString("packageName"))
                || !BuildConfig.UPDATE_CHANNEL.equals(document.getString("channel"))) throw new IOException("更新清单不属于本应用或通道");
        long code=document.getLong("versionCode");
        if (code<=installed) return null;
        if (document.getInt("minSdk")>sdk) throw new IOException("新版不支持当前 Android 系统");
        ReleaseArtifact selected=null;
        JSONArray entries=document.getJSONArray("artifacts");
        for(int i=0;i<entries.length();i++) {
            JSONObject item=entries.getJSONObject(i);
            if (!flavor.equals(item.getString("flavor")) || !abi.equals(item.getString("abi"))) continue;
            if(selected!=null) throw new IOException("更新清单包含重复安装包");
            selected=new ReleaseArtifact(item.getString("url"),item.getString("sha256"),item.getLong("size"),code,true);
        }
        if(selected==null) throw new IOException("暂未发布适合这台设备的安装包");
        return selected;
    }
    private static ReleaseArtifact select(JSONObject document) throws Exception {
        return select(document,BuildConfig.FLAVOR,android.os.Process.is64Bit()?"arm64-v8a":"armeabi-v7a",Build.VERSION.SDK_INT,BuildConfig.VERSION_CODE);
    }
    public static ReleaseArtifact ready() throws Exception {
        String text=prefs().getString("manifest","");
        if(text.isEmpty()) return null;
        ReleaseArtifact artifact=select(new JSONObject(text));
        return artifact!=null && file(artifact).isFile()?artifact:null;
    }
    public static boolean hasReadyFile() {
        try {return ready()!=null;} catch(Exception ignored) {return false;}
    }
    public void cancel() { cancelled=true; Call current=call; if(current!=null) current.cancel(); maintenancecore.UpdateDownload nativeCall=tunnel; if(nativeCall!=null)nativeCall.cancel(); }
    private void active() throws InterruptedIOException {
        if(cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("检查已停止，可重新检查");
    }
    private Response request(String url, boolean apk) throws IOException {
        active(); UpdateAddress.validate(url);
        call=ProductHttp.client().newBuilder().callTimeout(apk?90:20,TimeUnit.SECONDS).build()
                .newCall(new Request.Builder().url(url).build());
        if(cancelled) call.cancel();
        return call.execute();
    }
    public static boolean githubRelease(String url) {
        try { java.net.URI u=java.net.URI.create(url);
            return "https".equals(u.getScheme()) && "github.com".equals(u.getRawAuthority()) && u.getRawQuery()==null && u.getFragment()==null
                && !u.getRawPath().contains("%") && !u.getPath().contains("/../") && !u.getPath().contains("/./")
                && u.getPath().matches("/lijiangnian/CineLive/releases/(latest/download/manifest\\.json|download/[A-Za-z0-9._-]+/(manifest\\.json|CineLive-[A-Za-z0-9._-]+\\.apk))");
        } catch(Exception ignored) {return false;}
    }
    private void transfer(String url, File output, long limit, boolean apk) throws Exception {
        active(); receiving=apk?output:null;expected=apk?limit:0;stage=apk?"正在直连下载安装包":"正在读取更新清单…";
        try {
            try(Response response=request(url,apk)) {
                if(!response.isSuccessful() || response.body()==null || response.body().contentLength()>limit)
                    throw new IOException("下载服务响应异常");
                try(InputStream input=response.body().byteStream();OutputStream out=new FileOutputStream(output)) {
                    byte[] bytes=new byte[32768];long total=0;int n;
                    while((n=input.read(bytes))!=-1) {active();total+=n;if(total>limit)throw new IOException("下载超出大小限制");out.write(bytes,0,n);}
                    if(total==0)throw new IOException("空文件");
                }
            }
            prefs().edit().putString("route","直连").apply();
        } catch(Exception directFailure) {
            active();
            if(MaintenanceTransport.profile().isEmpty() || !githubRelease(url))throw directFailure;
            stage=apk?"直连失败，正在通过家庭线路下载":"直连失败，正在通过家庭线路读取清单…";
            try {
                tunnel=maintenancecore.Maintenancecore.newUpdateDownload(MaintenanceTransport.profile(),url,output.getAbsolutePath(),limit);
                active();tunnel.run();active();prefs().edit().putString("route","家庭线路（仅本应用 GitHub 更新）").apply();
            } catch(LinkageError e) {throw new IOException("当前设备无法启用更新线路");}
            finally {if(tunnel!=null)tunnel.cancel();tunnel=null;}
        } finally {receiving=null;expected=0;}
    }
    private JSONObject manifest(String url) throws Exception {
        File temp=new File(directory(),"manifest.part");
        try {
            transfer(url,temp,256*1024,false);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream input=new FileInputStream(temp)) {byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1)out.write(bytes,0,n);}
            JSONObject document=new JSONObject(out.toString("UTF-8"));select(document);return document;
        } finally {temp.delete();}
    }
    public String check(boolean download) throws Exception {
        if(!BUSY.compareAndSet(false,true)) throw new IOException("已有更新任务正在进行，请稍后查看");
        try {
            active();
            String primary=address();
            try { return checkAddress(primary,download); }
            catch(Exception first) {
                active(); String backup=prefs().getString("backup",NAS);
                if(backup.isEmpty() || backup.equals(primary)) throw first;
                return checkAddress(backup,download);
            }
        } catch(Exception failure) {
            if(!cancelled) save("暂时无法完成更新："+friendly(failure)+"。可重试；已下载的完整安装包会保留。",false);
            throw failure;
        } finally {call=null;BUSY.set(false);}
    }
    private String checkAddress(String address, boolean download) throws Exception {
        JSONObject document=manifest(address);
        active(); ReleaseArtifact artifact=select(document);
        if(artifact==null) return save("当前已是最新版本",true);
        if(!download) return save("发现新版 "+document.getString("versionName")+"，点击下载并准备安装",true);
        File target=file(artifact);boolean valid=false;
        if(target.isFile()) {
            try {artifact.verifyBytes(target);ApkVerifier.verifyApk(target,artifact);valid=true;}
            catch(Exception ignored) {target.delete();}
        }
        if(!valid) {
            File temp=new File(directory(),artifact.sha256+".part");
            try {
                if(directory().getUsableSpace()<artifact.size+8*1024*1024) throw new IOException("存储空间不足");
                for(int attempt=0;attempt<2;attempt++) {
                    try {
                        transfer(artifact.url,temp,artifact.size,true);stage="正在校验安装包…";
                        artifact.verifyBytes(temp);ApkVerifier.verifyApk(temp,artifact);active();
                        if(!temp.renameTo(target)) throw new IOException("无法保存安装包");
                        break;
                    } catch(Exception failure) {active();if(attempt==1)throw failure;}
                }
            } finally {temp.delete();}
        }
        active();prefs().edit().putString("manifest",document.toString()).apply();
        File[] old=directory().listFiles();if(old!=null)for(File item:old)if(!item.equals(target))item.delete();
        return save("新版 "+document.getString("versionName")+" 已下载并校验，等待确认安装",true);
    }
    private static String friendly(Exception error) {
        if(error instanceof java.net.SocketTimeoutException || error instanceof java.net.ConnectException || error instanceof java.net.UnknownHostException)
            return "无法连接更新服务，请检查是否在家中网络、极空间服务是否启动";
        if(error instanceof org.json.JSONException) return "地址返回的不是更新清单，请勿填写登录或分享页面";
        return "网络不可用或安装包校验失败";
    }
    private static String save(String message,boolean success) {
        var edit=prefs().edit().putString("status",message);
        if(success) edit.putLong("checked",System.currentTimeMillis());
        edit.apply();return message;
    }
}
