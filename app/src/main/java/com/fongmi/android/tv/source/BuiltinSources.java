package com.fongmi.android.tv.source;

import android.content.Context;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Curated subscription addresses only; no plugin execution on catalog open. */
public final class BuiltinSources {
    public static final class Entry {
        public final String name, note;
        public final int type;
        public final List<String> urls;
        Entry(String name, String note, int type, List<String> urls) {
            this.name=name; this.note=note; this.type=type; this.urls=Collections.unmodifiableList(urls);
        }
    }
    public static List<Entry> read(Context context, int type) throws IOException {
        String file=type==1 ? "cinelive-live-catalog.json" : "cinelive-vod-catalog.json";
        try (Reader reader=new InputStreamReader(context.getAssets().open(file), StandardCharsets.UTF_8)) {
            List<Entry> entries=new ArrayList<>();
            for (JsonElement item: JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("sources")) {
                JsonObject row=item.getAsJsonObject();
                List<String> urls=new ArrayList<>();
                for(JsonElement value: row.getAsJsonArray("urls")) {
                    okhttp3.HttpUrl url=okhttp3.HttpUrl.parse(value.getAsString());
                    if(url==null) throw new IOException("内置订阅地址无效");
                    urls.add(url.toString());
                }
                if(!urls.isEmpty()) entries.add(new Entry(row.get("name").getAsString(),
                        row.has("note") ? row.get("note").getAsString() : "选择后检查并启用",type,urls));
            }
            return entries;
        } catch(RuntimeException e) { throw new IOException("内置源目录读取失败",e); }
    }
    private BuiltinSources() {}
}
