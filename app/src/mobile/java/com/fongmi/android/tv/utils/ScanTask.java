package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.source.DeviceAddress;
import com.github.catvod.net.OkHttp;

import java.util.List;
import java.util.ArrayList;
import java.net.URI;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import okhttp3.OkHttpClient;
import okhttp3.Response;

public class ScanTask {

    private final CopyOnWriteArrayList<Future<?>> future;
    private final OkHttpClient client;
    private volatile Listener listener;
    private volatile int generation;

    public ScanTask(Listener listener) {
        this.client = OkHttp.client(1000);
        this.future = new CopyOnWriteArrayList<>();
        this.listener = listener;
    }

    public void start() {
        int current = restart();
        Task.execute(() -> run(getUrl(), current));
    }

    public void start(String url) {
        int current = restart();
        Task.execute(() -> run(List.of(url), current));
    }

    public void stop() {
        listener = null;
        restart();
    }

    private int restart() {
        int current = ++generation;
        OkHttp.cancel(client, "scan");
        future.forEach(f -> f.cancel(true));
        future.clear();
        return current;
    }

    private void run(List<String> urls, int current) {
        for (String url : urls) {
            if (generation != current || listener == null) return;
            future.add(Task.submitLarge(() -> findDevice(url, current)));
        }
    }

    private List<String> getUrl() {
        List<String> urls = new ArrayList<>();
        for (Device known : Device.getAll()) if (known.isApp()) urls.add(known.getIp());
        String host = URI.create(Server.get().getAddress()).getHost();
        if (host != null && host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
            String base = "http://" + host.substring(0, host.lastIndexOf('.') + 1);
            urls.addAll(IntStream.range(1, 255).mapToObj(i -> base + i + ":9978").toList());
        }
        return urls.stream().distinct().toList();
    }

    private void findDevice(String address, int current) {
        if (generation != current) return;
        try {
            String url = DeviceAddress.origin(address);
            if (url.equals(DeviceAddress.origin(Server.get().getAddress()))) return;
            try (Response res = OkHttp.newCall(client, url.concat("/device"), "scan").execute()) {
                if (!res.isSuccessful() || res.body() == null || res.body().contentLength() > 16 * 1024) return;
                okhttp3.ResponseBody body = res.peekBody(16 * 1024 + 1);
                if (body.contentLength() > 16 * 1024) return;
                Device device = Device.objectFrom(body.string());
                if (device == null || device.getUuid().isEmpty() || !device.isApp()) return;
                device.setIp(url);
                App.post(() -> {
                    if (listener != null && generation == current) listener.onFind(device.save());
                });
            }
        } catch (Exception ignored) {
        }
    }

    public interface Listener {

        void onFind(Device device);
    }
}
