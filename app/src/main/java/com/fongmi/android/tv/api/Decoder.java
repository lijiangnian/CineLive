package com.fongmi.android.tv.api;

import android.util.Base64;

import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.source.SourceStore;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Response;

public class Decoder {

    private static final Pattern JS_URI = Pattern.compile("\"(\\.|\\.\\.)/(.?|.+?)\\.js\\?(.?|.+?)\"");

    public static String getJson(String url, String tag) throws Exception {
        return getJson(url, tag, java.util.Collections.emptyMap());
    }

    public static String getJson(String url, String tag, java.util.Map<String, String> headers) throws Exception {
        okhttp3.Request request = new okhttp3.Request.Builder().url(url).tag(tag)
                .headers(okhttp3.Headers.of(headers)).build();
        okhttp3.Call call = OkHttp.client().newCall(request);
        call.timeout().timeout(30, java.util.concurrent.TimeUnit.SECONDS);
        try (Response res = call.execute()) {
            if (!res.isSuccessful()) throw new SourceStore.HttpFailure(res.code(), retryAfter(res.header("Retry-After")));
            if (res.body() == null || res.body().contentLength() > SourceStore.MAX_BYTES)
                throw new IOException("订阅为空或超出大小限制");
            HttpUrl httpUrl = res.request().url();
            int size = HttpUrl.parse(url).querySize();
            if (httpUrl.querySize() == size) url = httpUrl.toString();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = res.body().byteStream()) {
                byte[] buffer = new byte[8192];
                int sizeRead;
                while ((sizeRead = input.read(buffer)) != -1) {
                    if (output.size() + sizeRead > SourceStore.MAX_BYTES) throw new IOException("订阅超出大小限制");
                    output.write(buffer, 0, sizeRead);
                }
            }
            java.nio.charset.Charset charset = res.body().contentType() == null ? StandardCharsets.UTF_8
                    : res.body().contentType().charset(StandardCharsets.UTF_8);
            return verify(url, new String(output.toByteArray(), charset).replaceFirst("^\\uFEFF", ""));
        }
    }

    public static long retryAfter(String value) {
        if (value == null) return 0;
        try {
            long seconds = Long.parseLong(value.trim());
            return Math.min(Math.max(seconds, 0), 7 * 24 * 3600L) * 1000;
        } catch (NumberFormatException ignored) {
            try {
                long delay = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - System.currentTimeMillis();
                return Math.min(Math.max(delay, 0), 7 * 24 * 3600_000L);
            } catch (Exception invalid) { return 0; }
        }
    }

    public static String verify(String url, String data) throws Exception {
        if (data.isEmpty()) throw new Exception();
        if (Json.isObj(data)) return fix(url, data);
        if (data.contains("**")) data = base64(data);
        if (data.startsWith("2423")) data = cbc(data.replaceAll("\\s+", ""));
        return fix(url, data);
    }

    private static String fix(String url, String data) {
        Matcher matcher = JS_URI.matcher(data);
        while (matcher.find()) data = replace(url, data, matcher.group());
        if (data.contains("../")) data = data.replace("../", UrlUtil.resolve(url, "../"));
        if (data.contains("./")) data = data.replace("./", UrlUtil.resolve(url, "./"));
        if (data.contains("__JS1__")) data = data.replace("__JS1__", "./");
        if (data.contains("__JS2__")) data = data.replace("__JS2__", "../");
        return data;
    }

    private static String replace(String url, String data, String ext) {
        String t = ext.replace("\"./", "\"" + UrlUtil.resolve(url, "./"));
        t = t.replace("\"../", "\"" + UrlUtil.resolve(url, "../"));
        t = t.replace("./", "__JS1__").replace("../", "__JS2__");
        return data.replace(ext, t);
    }

    private static String cbc(String data) throws Exception {
        String decode = new String(Util.hex2byte(data), StandardCharsets.UTF_8).toLowerCase();
        String key = padEnd(decode.substring(decode.indexOf("$#") + 2, decode.indexOf("#$")));
        String iv = padEnd(decode.substring(decode.length() - 13));
        data = data.substring(data.indexOf("2324") + 4, data.length() - 26);
        byte[] decrypted = Crypto.decryptAesCbc(Util.hex2byte(data), key.getBytes(StandardCharsets.UTF_8), iv.getBytes(StandardCharsets.UTF_8));
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private static String base64(String data) {
        String extract = extract(data);
        if (extract.isEmpty()) return data;
        return new String(Base64.decode(extract, Base64.DEFAULT));
    }

    private static String extract(String data) {
        Matcher matcher = Pattern.compile("[A-Za-z0-9]{8}\\*\\*").matcher(data);
        return matcher.find() ? data.substring(data.indexOf(matcher.group()) + 10) : "";
    }

    private static String padEnd(String key) {
        return key + "0000000000000000".substring(key.length());
    }
}
