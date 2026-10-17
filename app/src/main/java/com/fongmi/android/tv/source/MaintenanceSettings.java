package com.fongmi.android.tv.source;

import android.content.SharedPreferences;
import com.fongmi.android.tv.App;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Each subscription keeps its own publisher mirrors; accounts and other configs stay untouched. */
public final class MaintenanceSettings {
    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences("source-maintenance", 0);
    }
    public static boolean enabled() { return prefs().getBoolean("enabled", true); }
    public static void enabled(boolean enabled) { prefs().edit().putBoolean("enabled", enabled).apply(); if(!enabled)LiveHealth.stopBackground(); }
    public static String mirrors(String key) { return prefs().getString("mirrors:" + key, ""); }
    public static List<String> addresses(String key, String primary) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        urls.add(primary);
        for (String url : mirrors(key).split("\\r?\\n")) if (!url.trim().isEmpty()) urls.add(url.trim());
        return new ArrayList<>(urls);
    }
    public static void save(String key, String text) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String line : text.split("\\r?\\n")) {
            String url = line.trim();
            if (url.isEmpty()) continue;
            URI uri;
            try { uri = URI.create(url); } catch (Exception e) { throw new IllegalArgumentException("请填写完整的 HTTP / HTTPS 订阅地址"); }
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("请填写完整的 HTTP / HTTPS 订阅地址");
            urls.add(url);
        }
        if (urls.size() > 5) throw new IllegalArgumentException("最多设置 5 个备用地址");
        if (!prefs().edit().putString("mirrors:" + key, String.join("\n", urls)).commit())
            throw new IllegalStateException("备用地址未保存，请重试");
    }
    public static void used(String key, int index) { prefs().edit().putInt("used:" + key, index).apply(); }
    public static int used(String key) { return prefs().getInt("used:" + key, 0); }
    private MaintenanceSettings() {}
}
