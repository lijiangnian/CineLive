package com.fongmi.android.tv.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Product updates never inherit source plugins' cookies, proxies or permissive TLS settings. */
public final class ProductHttp {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .addNetworkInterceptor(chain -> {
                try { UpdateAddress.validate(chain.request().url().toString()); }
                catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
                return chain.proceed(chain.request());
            }).build();

    private ProductHttp() { }
    public static OkHttpClient client() { return CLIENT; }

    public static String text(String url, int limit) throws IOException {
        Request request = new Request.Builder().url(url).build();
        UpdateAddress.validate(url);
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("更新服务暂不可用");
            if (response.body().contentLength() > limit) throw new IOException("更新清单超出大小限制");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = response.body().byteStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (output.size() + size > limit) throw new IOException("更新清单超出大小限制");
                    output.write(buffer, 0, size);
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
