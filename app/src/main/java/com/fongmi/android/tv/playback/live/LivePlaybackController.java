package com.fongmi.android.tv.playback.live;

import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.playback.PlaybackResult;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.setting.LiveSetting;

public class LivePlaybackController {

    private java.util.concurrent.Future<?> verification;
    private int verificationId;
    private boolean verifying;
    private boolean released;
    private LivePlayRequest deferredRequest;
    private final Runnable serviceDeadline=this::serviceExpired;
    private void serviceExpired(){
        if(!released && !host.isPlaybackServiceReady())host.resetPlaybackForError("播放器连接超时，请返回直播重试");
    }
    private void waitForService(){
        host.showProgress();
        com.fongmi.android.tv.App.removeCallbacks(serviceDeadline);
        com.fongmi.android.tv.App.post(serviceDeadline,15_000);
    }
    public void onPlaybackServiceLost(){
        if(released)return;
        LivePlayRequest active=state.getActiveRequest();
        if(active!=null)deferredRequest=active;
        cancelVerification();state.clearPlayback();
        if(state.getChannel()!=null)waitForService();
    }
    private final java.util.Set<String> attempted = new java.util.HashSet<>();
    private final Runnable verificationDeadline=this::verificationExpired;
    private void verificationExpired(){if(released)return;cancelVerification();playOriginalLine();}
    private final Runnable stalled = () -> playbackError("直播连接超时");
    private void cancelVerification() {
        verificationId++; verifying=false;
        if(verification!=null)verification.cancel(true);
        com.fongmi.android.tv.App.removeCallbacks(stalled);
        com.fongmi.android.tv.App.removeCallbacks(verificationDeadline);
        com.fongmi.android.tv.App.removeCallbacks(serviceDeadline);
    }
    public void buffering() {
        if(released || verifying || !host.isPlaybackServiceReady())return;
        com.fongmi.android.tv.App.removeCallbacks(stalled);
        com.fongmi.android.tv.App.post(stalled,20_000);
    }
    public void ready() { com.fongmi.android.tv.App.removeCallbacks(stalled); }
    public void release() { released=true;deferredRequest=null;cancelVerification(); }

    private final LivePlaybackState state;
    private final LivePlaybackHost host;

    public LivePlaybackController(LivePlaybackHost host, LivePlaybackState state) {
        this.state = state;
        this.host = host;
    }

    public void reset() {
        cancelVerification();attempted.clear();deferredRequest=null;
        state.reset();
    }

    public void selectGroup(Group group) {
        state.setGroup(group);
        host.renderGroupSelection(group);
        host.renderGroupChannels(group);
    }

    public void selectChannel(Channel channel) {
        if (released || channel == null) return;
        LivePlayRequest activeRequest = state.getActiveRequest();
        boolean restore = activeRequest != null && activeRequest.matches(channel);
        state.setChannel(channel);
        host.renderChannelSelection(channel);
        if (restore) restorePlaybackIfNeeded();
        else {
            cancelVerification();attempted.clear();
            deferredRequest=null;state.clearPlayback();
            prepareLive();
        }
    }

    public boolean selectEpg(EpgData data) {
        return selectEpg(data, C.TIME_UNSET);
    }

    public boolean selectEpg(EpgData data, long startPositionMs) {
        Channel channel = state.getChannel();
        if (released || channel == null || data == null) return false;
        if (data.isSelected()) return requestCatchup(data, startPositionMs);
        if (!channel.hasCatchup() && !channel.isRtsp()) return false;
        host.showCatchupReady(data);
        host.renderEpgSelection(data);
        return requestCatchup(data, C.TIME_UNSET);
    }

    public void onPlaybackResult(PlaybackResult<LivePlayRequest> playback) {
        if (released || !host.isPlaybackServiceReady() || playback == null || cannotApply(playback)) return;
        applyPlaybackResult(playback.result(), playback.request());
    }

    private void applyPlaybackResult(Result result, LivePlayRequest request) {
        String realUrl = result.getRealUrl();
        if (result.hasMsg() || TextUtils.isEmpty(realUrl)) failPlayback(result.getMsg());
        else startResolvedPlayback(result, request, realUrl);
    }

    private void startResolvedPlayback(Result result, LivePlayRequest request, String realUrl) {
        long position = result.hasPosition() ? result.getPosition() : request.getPosition();
        state.setPlayingRequest(request, realUrl);
        host.startPlayback(result, position, publishPlaybackMetadata(getEpgData(request)));
    }

    private void failPlayback(String msg) {
        // Keep the failed request's catchup intent while deciding whether live fallback is allowed.
        LivePlayRequest failedRequest = state.getPendingRequest();
        playbackError(msg);
        if (state.getPendingRequest() == failedRequest) state.clearPendingRequest();
    }

    public void playbackError(String msg) {
        if(released || verifying)return;
        if(!host.isPlaybackServiceReady()){onPlaybackServiceLost();return;}
        com.fongmi.android.tv.App.removeCallbacks(stalled);
        host.resetPlaybackForError(msg);
        fallbackAfterError();
    }

    public void playbackEnded() {
        if(released || !host.isPlaybackServiceReady())return;
        if (host.isPlayerLive()) playNextProgram();
        else nextChannel();
    }

    public void onEpgChanged(EpgData data) {
        if (state.getChannel() != null) publishPlaybackMetadata(data);
    }

    public void refresh() {
        if(released)return;
        cancelVerification();attempted.clear();
        Channel channel = state.getChannel();
        if (channel == null) return;
        LivePlayRequest request = deferredRequest!=null ? deferredRequest : state.getActiveRequest();
        if (request != null && request.isCatchup() && request.matches(channel)) {
            long startPositionMs = host.isPlaybackServiceReady() && host.hasPlaybackSession() ? host.getPlayerPosition() : request.getPosition();
            requestPlayback(LivePlayRequest.catchup(channel, request.getCatchupData(), startPositionMs), true);
        } else {
            requestLive();
        }
    }

    public void onPlaybackServiceReady() {
        if(released || !host.isPlaybackServiceReady())return;
        com.fongmi.android.tv.App.removeCallbacks(serviceDeadline);
        if(deferredRequest!=null){
            LivePlayRequest request=deferredRequest;deferredRequest=null;
            if(request.matches(state.getChannel())){requestPlayback(request,true);return;}
        }
        host.restorePlaybackKey(state.getPlaybackKey());
        syncPlaybackMetadata();
        if (restorePlaybackIfNeeded()) return;
        if (state.getChannel() != null && state.getActiveRequest() == null && !verifying) prepareLive();
    }

    private void syncPlaybackMetadata() {
        MediaMetadata metadata = state.getPlaybackMetadata();
        if (metadata != null) host.renderPlaybackMetadata(metadata);
    }

    private boolean restorePlaybackIfNeeded() {
        LivePlayRequest request = state.getPlayingRequest();
        if (request == null || !request.matches(state.getChannel()) || host.hasPlaybackSession()) return false;
        refresh();
        return true;
    }

    public void prevChannel() {
        moveChannel(-1);
    }

    public void nextChannel() {
        moveChannel(1);
    }

    public void prevLine() {
        switchLine(false, true);
    }

    public void nextLine(boolean show) {
        switchLine(true, show);
    }

    private void switchLine(boolean next, boolean show) {
        Channel channel = state.getChannel();
        if (released || channel == null || channel.isOnly()) return;
        deferredRequest=null;
        cancelVerification();attempted.clear();
        channel.switchLine(next);
        host.renderLineSelection(channel, show);
        requestLive();
    }

    private void moveChannel(int delta) {
        Group group = state.getGroup();
        if (group == null || group.isEmpty()) return;
        int size = group.getChannel().size();
        int position = group.getPosition() + delta;
        boolean limit = position < 0 || position >= size;
        if (LiveSetting.isAcross() && limit) moveGroup(delta);
        else group.setPosition(limit ? wrap(position, size) : position);
        group = state.getGroup();
        if (group != null && !group.isEmpty()) selectChannel(group.current());
    }

    private void moveGroup(int delta) {
        int count = host.getGroupCount();
        if (count <= 1) return;
        Group current = state.getGroup();
        int position = host.getGroupPosition();
        for (int i = 0; i < count; i++) {
            position = wrap(position + delta, count);
            Group target = host.getGroup(position);
            if (target.equals(current)) return;
            if (target.skip() || target.isEmpty()) continue;
            state.setGroup(target);
            host.renderGroupSelection(target);
            host.renderGroupChannels(target);
            target.setPosition(delta > 0 ? 0 : target.getChannel().size() - 1);
            return;
        }
    }

    private int wrap(int position, int size) {
        return ((position % size) + size) % size;
    }

    private void fallbackAfterError() {
        Channel channel = state.getChannel();
        LivePlayRequest request=state.getActiveRequest();
        if (!LiveSetting.isChange() || channel == null || (request!=null && request.isCatchup())) return;
        if(com.fongmi.android.tv.source.LiveHealth.applies(channel)) {
            attempted.add(channel.getCurrent());
            com.fongmi.android.tv.source.LiveHealth.failed(channel);
            prepareLive();
        } else if(!channel.isLast()) nextLine(true);
    }

    private void prepareLive() {
        Channel selected=state.getChannel();
        if(released || selected==null)return;
        if(!host.isPlaybackServiceReady()){waitForService();return;}
        if(!LiveSetting.isChange() || !com.fongmi.android.tv.source.LiveHealth.applies(selected)) {requestLive();return;}
        cancelVerification();
        if(attempted.size()>=8){host.resetPlaybackForError("此频道线路暂不可用，可稍后重试或选择其他频道");return;}
        int request=verificationId;
        verifying=true;host.stopPlaybackForRefresh();host.showProgress();
        com.fongmi.android.tv.App.post(verificationDeadline,12_000);
        verification=com.fongmi.android.tv.source.LiveHealth.prepare(selected,new java.util.HashSet<>(attempted),found->{
            if(released || request!=verificationId || state.getChannel()!=selected)return;
            if(!host.isPlaybackServiceReady()){onPlaybackServiceLost();return;}
            verifying=false;com.fongmi.android.tv.App.removeCallbacks(verificationDeadline);
            if(found==null){playOriginalLine();return;}
            // Retain original lines and keep alternate provider headers scoped to that line.
            selected.addVerifiedLine(found);
            host.renderLineSelection(selected,false);requestLive();
        });
    }

    // A failed/unsupported Exo probe must not veto the user's original MPV-capable line.
    // Original lines are tried by the actual player, never promoted to verified backups here.
    private void playOriginalLine(){
        Channel channel=state.getChannel();
        if(released || channel==null)return;
        if(!host.isPlaybackServiceReady()){waitForService();return;}
        int start=channel.getIndex();
        for(int i=0;i<channel.getUrls().size() && attempted.size()<8;i++){
            channel.setIndex((start+i)%channel.getUrls().size());
            if(channel.getCurrent().isEmpty() || !attempted.add(channel.getCurrent()))continue;
            host.renderLineSelection(channel,false);requestLive();return;
        }
        host.resetPlaybackForError("此频道线路暂不可用，可稍后重试或选择其他频道");
    }

    private void playNextProgram() {
        Channel channel = state.getChannel();
        if (channel == null) return;
        EpgData data = getNextEpgData(channel);
        if (data != null && selectEpg(data)) return;
        data = getCurrentEpgData(channel);
        if (data != null) host.renderEpgSelection(data);
        refresh();
    }

    private void requestLive() {
        Channel channel = state.getChannel();
        if (released || channel == null) return;
        LiveConfig.get().setKeep(channel);
        requestPlayback(LivePlayRequest.live(channel, C.TIME_UNSET), true);
    }

    private boolean requestCatchup(EpgData data, long startPositionMs) {
        Channel channel = state.getChannel();
        if (channel == null) return false;
        cancelVerification();attempted.clear();
        requestPlayback(LivePlayRequest.catchup(channel, data, startPositionMs), false);
        return true;
    }

    private void requestPlayback(LivePlayRequest request, boolean showProgress) {
        if(released)return;
        if (!host.isPlaybackServiceReady()) {deferredRequest=request;waitForService();return;}
        deferredRequest=null;
        state.setPendingRequest(request);
        host.stopPlaybackForRefresh();
        publishPlaybackMetadata(getEpgData(request));
        host.requestUrl(request);
        if (showProgress) {host.showProgress();buffering();}
    }

    private EpgData getEpgData(LivePlayRequest request) {
        return request.isCatchup() ? request.getCatchupData() : getCurrentEpgData(request.getChannel());
    }

    @Nullable
    private EpgData getNextEpgData(Channel channel) {
        if (channel == null) return null;
        Epg epg = channel.getData(host.getZoneId());
        int current = epg.getInRange();
        int position = epg.getSelected() + 1;
        return position <= current && position > 0 && position < epg.getList().size() ? epg.getList().get(position) : null;
    }

    @Nullable
    private EpgData getCurrentEpgData(Channel channel) {
        if (channel == null) return null;
        Epg epg = channel.getData(host.getZoneId()).selected();
        int position = epg.getSelected();
        return position >= 0 && position < epg.getList().size() ? epg.getList().get(position) : null;
    }

    private MediaMetadata publishPlaybackMetadata(@Nullable EpgData liveData) {
        LivePlayRequest request = state.getActiveRequest();
        Channel channel = request == null ? state.getChannel() : request.getChannel();
        EpgData data = request != null && request.isCatchup() ? request.getCatchupData() : liveData;
        MediaMetadata metadata = buildPlaybackMetadata(channel, data);
        state.setPlaybackMetadata(metadata);
        host.renderPlaybackMetadata(metadata);
        return metadata;
    }

    private MediaMetadata buildPlaybackMetadata(Channel channel, EpgData data) {
        String title = channel == null ? "" : channel.getShow();
        String logo = channel == null ? "" : channel.getLogo();
        String artist = data == null ? "" : data.format();
        String name = data == null ? "" : data.getTitle();
        return MediaItemFactory.buildMetadata(title, artist, logo, name);
    }

    private boolean cannotApply(PlaybackResult<LivePlayRequest> playback) {
        LivePlayRequest pending = state.getPendingRequest();
        LivePlayRequest request = playback.request();
        return pending == null || pending != request || !request.matches(state.getChannel());
    }
}
