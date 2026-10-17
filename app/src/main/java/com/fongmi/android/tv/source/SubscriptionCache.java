package com.fongmi.android.tv.source;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.parser.LiveParser;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;

/** Does not load or execute subscription plugins during background validation. */
public final class SubscriptionCache {
    public enum Kind { VOD, LIVE }
    private static SourceStore store;

    private static synchronized SourceStore store() {
        if (store == null) store = new SourceStore(new File(App.get().getFilesDir(), "subscriptions"));
        return store;
    }

    public static String load(String url, String tag, Kind kind) throws Exception {
        SourceStore.Document document;
        try { document = MaintenanceTransport.eligible(url) ? cached(url, kind) : refresh(url, tag, kind, true); }
        catch (IOException missing) { document = MaintenanceTransport.eligible(url)
                ? refreshForMaintenance(url, tag, kind, true) : refresh(url, tag, kind, true); }
        if (document.fallback) App.post(() -> Notify.show("订阅更新失败，正在使用上次缓存"));
        return document.text;
    }

    public static SourceStore.Document refresh(String url, String tag, Kind kind, boolean force) throws Exception {
        return refreshWithMirrors(key(url, kind), url, address -> Decoder.getJson(address, tag),
                text -> validate(text, kind), force);
    }

    public static SourceStore.Document refreshForMaintenance(String url, String tag, Kind kind, boolean force) throws Exception {
        return refreshWithMirrors(key(url,kind),url,address -> MaintenanceTransport.fetch(address,kind),
                text -> validate(text,kind),force);
    }

    public static String key(String url, Kind kind) { return kind.name() + ":" + url; }

    private static SourceStore.Document refreshWithMirrors(String key, String primary,
            SourceFailover.Fetcher fetcher, SourceStore.Validator validator, boolean force) throws Exception {
        int oldCount = 0;
        try { oldCount = store().cached(key).entries; } catch (IOException ignored) { }
        final int previousEntries = oldCount;
        final SourceFailover.Candidate[] selected = {null};
        SourceStore.Document result = store().refresh(key, () -> {
            selected[0] = SourceFailover.fetch(MaintenanceSettings.addresses(key, primary), fetcher,
                    validator, previousEntries);
            return selected[0].text;
        }, validator, System.currentTimeMillis(), force);
        if (selected[0] != null && !result.fallback && result.text.equals(selected[0].text))
            MaintenanceSettings.used(key, selected[0].addressIndex);
        return result;
    }

    public static SourceStore.Document status(String url, Kind kind) throws IOException {
        return store().status(key(url, kind));
    }

    public static SourceStore.Document cached(String url, Kind kind) throws IOException {
        return store().cached(kind.name() + ":" + url);
    }

    public static String loadPlaylist(String url, Map<String, String> headers) throws Exception {
        SourceStore.Document document = refreshPlaylist(url, headers, true);
        if (document.fallback) App.post(() -> Notify.show("直播列表更新失败，正在使用上次缓存"));
        return document.text;
    }

    public static SourceStore.Document refreshPlaylist(String url, Map<String, String> headers, boolean force) throws Exception {
        // Account-specific header sets must never share a cached private playlist.
        String key = "PLAYLIST:" + url + ":" + App.gson().toJson(new TreeMap<>(headers));
        // Playlist headers can hold an account token: never forward them to another host.
        // Keep playlist fetching scoped to its original URL; mirrors apply to configuration documents.
        return store().refresh(key, () -> Decoder.getJson(url, force ? "PlaylistLoad" : "PlaylistMaintenance", headers),
                SubscriptionCache::validatePlaylist, System.currentTimeMillis(), force);
    }

    private static int validatePlaylist(String text) throws Exception {
        if (Json.isArray(text)) {
            return (int) Group.arrayFrom(text).stream().flatMap(group -> group.getChannel().stream())
                    .filter(channel -> !channel.getName().isEmpty() && !channel.getUrls().isEmpty()).count();
        }
        if (Json.isObj(text)) throw new IOException("直播列表格式错误");
        return validate(text, Kind.LIVE);
    }

    static int validate(String text, Kind kind) throws Exception {
        if (Json.isObj(text)) {
            JsonObject root = Json.parse(text).getAsJsonObject();
            if (root.has("msg")) throw new IOException("订阅返回错误信息");
            String field = root.has("urls") ? "urls" : kind == Kind.VOD ? "sites" : "lives";
            if (!root.has(field) || !root.get(field).isJsonArray()) throw new IOException("订阅缺少列表");
            JsonArray items = root.getAsJsonArray(field);
            Set<String> keys = new HashSet<>();
            for (JsonElement item : items) {
                if (!item.isJsonObject()) throw new IOException("订阅条目格式错误");
                JsonObject object = item.getAsJsonObject();
                String keyField = field.equals("sites") ? "key" : field.equals("urls") ? "url" : "name";
                String key = required(object, keyField);
                if (!keys.add(key)) throw new IOException("订阅条目重复");
                if (field.equals("sites")) {
                    required(object, "name");
                    required(object, "api");
                    if (!object.has("type")) throw new IOException("站点缺少类型");
                    int type = object.get("type").getAsInt();
                    if (type < 0 || type > 4) throw new IOException("站点类型不支持");
                }
                if (field.equals("lives") && !object.has("url") && !object.has("channels")
                        && !object.has("groups") && !object.has("api"))
                    throw new IOException("直播源缺少地址或频道");
            }
            return items.size();
        }
        if (kind != Kind.LIVE || text.contains("#EXT-X-TARGETDURATION") || text.contains("#EXT-X-STREAM-INF"))
            throw new IOException("不是有效订阅列表");
        Live candidate = new Live("validate", "");
        LiveParser.text(candidate, text);
        return (int) candidate.getGroups().stream().flatMap(group -> group.getChannel().stream())
                .filter(channel -> !channel.getName().isEmpty() && !channel.getUrls().isEmpty()).count();
    }

    private static String required(JsonObject object, String key) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || object.get(key).getAsString().trim().isEmpty()) throw new IOException("订阅缺少必填字段");
        return object.get(key).getAsString();
    }
}
