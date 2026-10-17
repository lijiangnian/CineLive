package com.fongmi.android.tv.source;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;

import java.util.concurrent.Future;

/** Stages document updates only; does not replace an active playback session or run plugins. */
public class SubscriptionJob extends JobService {
    private static final int ID = 0x43534D;
    private static final String TAG = "SubscriptionMaintenance";
    private Future<?> future;
    private volatile int generation;

    public static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo pending=scheduler.getPendingJob(ID);
        if(pending!=null && pending.getIntervalMillis()==15*60_000L)return;
        scheduler.schedule(new JobInfo.Builder(ID, new ComponentName(context, SubscriptionJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60_000L).build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!MaintenanceSettings.enabled()) return false;
        int current = ++generation;
        future = Task.submit(() -> {
            try {
                refresh(Config.vod(), SubscriptionCache.Kind.VOD);
                if (!Thread.currentThread().isInterrupted()) refresh(Config.live(), SubscriptionCache.Kind.LIVE);
                if (!Thread.currentThread().isInterrupted()) refreshPlaylist();
                if (!Thread.currentThread().isInterrupted()) LiveHealth.maintain();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                App.post(() -> { if (generation == current) jobFinished(params, false); });
            }
        });
        return true;
    }

    private void refresh(Config config, SubscriptionCache.Kind kind) {
        String url = UrlUtil.convert(config.getUrl());
        if (!url.startsWith("https://") && !url.startsWith("http://")) return;
        try { SubscriptionCache.refreshForMaintenance(url, TAG, kind, false); }
        catch (Exception ignored) { /* SourceStore persists the failure and next retry time. */ }
    }

    private void refreshPlaylist() {
        Live live = LiveConfig.get().getHome();
        if (!live.getApi().isEmpty()) return;
        String url = UrlUtil.convert(live.getUrl());
        if (!url.startsWith("https://") && !url.startsWith("http://")) return;
        try {
            SourceStore.Document document=SubscriptionCache.refreshPlaylist(url, live.getHeaders(), false);
            com.google.gson.JsonObject entry=new com.google.gson.JsonObject();
            entry.addProperty("name",live.getName());entry.addProperty("url",url);
            entry.add("header",App.gson().toJsonTree(live.getHeaders()));
            Live candidate=Live.objectFrom(entry,"");
            if(com.github.catvod.utils.Json.isArray(document.text)){
                candidate.getGroups().addAll(com.fongmi.android.tv.bean.Group.arrayFrom(document.text));
                for(var group:candidate.getGroups())for(var channel:group.getChannel())channel.live(candidate);
            }
            else com.fongmi.android.tv.api.parser.LiveParser.text(candidate,document.text);
            LiveHealth.observePlaylist(candidate);
        }
        catch (Exception ignored) { }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        ++generation;
        if (future != null) future.cancel(true);
        LiveHealth.stopBackground();
        OkHttp.cancel(TAG);
        OkHttp.cancel("PlaylistMaintenance");
        return true;
    }
}
