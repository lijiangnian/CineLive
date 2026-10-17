package com.fongmi.android.tv.source;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.Decoder;
import java.io.*;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import maintenancecore.Maintenancecore;

/** The sole caller of the private native route. No global proxy or VPN service. */
public final class MaintenanceTransport {
    private static final OkHttpClient DIRECT = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .callTimeout(12, TimeUnit.SECONDS).connectTimeout(6, TimeUnit.SECONDS).build();
    private static String profile;
    static synchronized String profile() {
        if (profile == null) {
            profile = App.get().getSharedPreferences("private-route",0).getString("profile","");
            if (App.get().getSharedPreferences("private-route",0).contains("profile")) return profile;
            try (InputStream in = App.get().getAssets().open("family-maintenance.txt")) {
                byte[] value = read(in,4097);
                if (value.length <= 4096) profile = new String(value, StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) { }
            if (!profile.isEmpty()) App.get().getSharedPreferences("private-route",0).edit().putString("profile",profile).commit();
        }
        return profile;
    }
    public static void prepare() { profile(); }
    public static boolean configured() { return !profile().isEmpty(); }
    public static synchronized void configure(String value) throws Exception {
        String next=value.trim();
        if(!next.isEmpty()) Maintenancecore.checkProfile(next);
        if(!App.get().getSharedPreferences("private-route",0).edit().putString("profile",next).commit()) throw new IOException("无法保存配置");
        profile=next;
    }
    public static boolean eligible(String url) {
        // Check Java assets as well as the native exact URL allowlist; never host-wide routing.
        try {
            boolean listed = false;
            for (int type=0; type<2; type++) for (BuiltinSources.Entry e : BuiltinSources.read(App.get(), type))
                if (e.urls.contains(url)) listed = true;
            return listed && (url.startsWith("https://raw.githubusercontent.com/") || url.startsWith("https://iptv-org.github.io/"));
        } catch (Exception | LinkageError ignored) { return false; }
    }
    public static String description() {
        return profile().isEmpty() ? "配置更新：直连" : "配置更新：优先直连，指定内置订阅失败时使用家庭线路\n播放、搜索及插件下载保持直连。";
    }
    public static String route(String url) {
        return App.get().getSharedPreferences("maintenance-route",0).getString(url,"尚未检查");
    }
    private static void route(String url,String value) {
        App.get().getSharedPreferences("maintenance-route",0).edit().putString(url,value).apply();
    }
    public static String fetch(String url, SubscriptionCache.Kind kind) throws Exception {
        try {
            String text = direct(url);
            if (SubscriptionCache.validate(text,kind) <= 0) throw new IOException("空订阅");
            route(url,"直连"); return text;
        } catch (Exception directFailure) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!eligible(url) || profile().isEmpty()) throw directFailure;
            if (directFailure instanceof SourceStore.HttpFailure failure
                    && (failure.status == 404 || failure.status == 429 || failure.retryAfterMs > 0)) throw directFailure;
            try {
                maintenancecore.Result result = Maintenancecore.fetch(profile(),url);
                if (result.getStatus()<200 || result.getStatus()>=300)
                    throw new SourceStore.HttpFailure((int)result.getStatus(),Decoder.retryAfter(result.getRetryAfter()));
                String text = Decoder.verify(result.getURL(),new String(result.getBody(),StandardCharsets.UTF_8).replaceFirst("^\\uFEFF",""));
                if (SubscriptionCache.validate(text,kind)<=0) throw new IOException("空订阅");
                route(url,"家庭线路（仅配置）");return text;
            } catch (Exception | LinkageError failure) {
                route(url,"本次更新未成功");
                if (failure instanceof SourceStore.HttpFailure http) throw http;
                throw new IOException("配置更新暂不可用");
            }
        }
    }
    private static String direct(String url) throws Exception {
        try (Response response=DIRECT.newCall(new Request.Builder().url(url).build()).execute()) {
            if(!response.isSuccessful()) throw new SourceStore.HttpFailure(response.code(),Decoder.retryAfter(response.header("Retry-After")));
            if(response.body()==null || response.body().contentLength()>SourceStore.MAX_BYTES)throw new IOException("订阅大小无效");
            try(InputStream in=response.body().byteStream()) {
                byte[] bytes=read(in,SourceStore.MAX_BYTES+1);
                if(bytes.length>SourceStore.MAX_BYTES)throw new IOException("订阅过大");
                return Decoder.verify(response.request().url().toString(),new String(bytes,StandardCharsets.UTF_8).replaceFirst("^\\uFEFF",""));
            }
        }
    }
    private static byte[] read(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
        while(out.size()<limit){int n=in.read(buffer,0,Math.min(buffer.length,limit-out.size()));if(n<0)break;out.write(buffer,0,n);}
        return out.toByteArray();
    }
    private MaintenanceTransport() {}
}
