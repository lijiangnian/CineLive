package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.List;

class VodFallbackPolicy {

    private final VodPlaybackController controller;
    private final VodPlaybackState state;
    private final VodPlaybackHost host;
    private boolean searched;
    private int switches;
    private Vod expected;
    private String episodeName="";
    private long resumePosition=androidx.media3.common.C.TIME_UNSET;

    void reset(){searched=false;switches=0;expected=null;episodeName="";resumePosition=androidx.media3.common.C.TIME_UNSET;}
    void remember(Vod item){if(!state.isAutoFallback() || expected==null)expected=item;}
    boolean accepts(Vod item){return !state.isAutoFallback() || VodIdentity.sameTitle(expected,item);}
    boolean movie(){return expected!=null && expected.getTypeName().contains("电影");}
    void rejected(){fallbackToNextSource();}
    String targetEpisode(){return episodeName;}
    long resumePosition(){return resumePosition;}
    void beginEpisode(String name){
        if(!episodeName.isEmpty() && !VodIdentity.sameEpisode(episodeName,name)){searched=false;switches=0;state.clearFailedIds();}
        episodeName=name;
    }
    private String id(String site,String id){return site+"\n"+id;}
    private void exhausted(){state.setSelectFirstSource(false);host.showDetailMessage("没有找到可确认的同片同集备用，可手动选择其他来源。");}

    VodFallbackPolicy(VodPlaybackController controller, VodPlaybackState state, VodPlaybackHost host) {
        this.controller = controller;
        this.state = state;
        this.host = host;
    }

    void playbackError() {
        if(state.getHistory()!=null)resumePosition=state.getHistory().getPosition();
        fallbackToNextLineOrSource();
    }

    void emptyFlag() {
        fallbackToNextLineOrSource();
    }

    void emptyDetail() {
        fallbackToNextSource();
    }

    void manualSwitchSource() {
        searched=false;switches=0;state.clearFailedIds();
        fallbackToNextSource();
    }

    void search(String keyword, boolean autoFallback) {
        if(keyword==null || keyword.trim().isEmpty()){exhausted();return;}
        if(autoFallback && searched){exhausted();return;}
        state.setSources(java.util.Collections.emptyList());
        searched=autoFallback;
        state.setSearchKeyword(keyword);
        state.setAutoFallback(autoFallback);
        state.setSelectFirstSource(autoFallback);
        host.onSearchStarted(keyword);
        host.requestSearch(getSearchableSites(), keyword);
    }

    void onSearchResult(Result result) {
        List<Vod> items = new ArrayList<>(result.getList());
        items.removeIf(this::mismatch);
        List<Vod> queued=new ArrayList<>(state.getSources());
        for(Vod item:items)if(queued.stream().noneMatch(existing -> id(existing.getSiteKey(),existing.getId()).equals(id(item.getSiteKey(),item.getId()))))queued.add(item);
        state.setSources(queued);
        host.renderSources(state.getSources());
        if (state.isSelectFirstSource() && state.hasSources()) nextSource();
        if (items.isEmpty()) return;
        host.onSearchResult();
    }

    private void fallbackToNextLineOrSource() {
        if (!host.isSiteChangeable()) return;
        if (fallbackToNextLine()) return;
        fallbackToNextSource();
    }

    private boolean fallbackToNextLine() {
        if(!state.hasEpisode())return false;
        String name=state.getEpisode().getName();
        for(int position=state.getFlagPosition()+1;position<state.getFlags().size();position++){
            Flag flag=state.getFlags().get(position);
            com.fongmi.android.tv.bean.Episode episode=VodIdentity.episode(flag,name,movie());
            if(episode==null)continue;
            host.showSwitchLine(flag);controller.fallbackLine(flag,episode);return true;
        }
        return false;
    }

    private void fallbackToNextSource() {
        if (!state.hasSources()) search(host.getVodName(), true);
        else {state.setAutoFallback(true);nextSource();}
    }

    private void nextSource() {
        if (!state.hasSources()){exhausted();return;}
        if(switches++>=8){exhausted();return;}
        Vod item = state.removeFirstSource();
        host.renderSources(state.getSources());
        host.showSwitchSource(item);
        state.addFailedId(id(host.getVodKey(),host.getVodId()));
        state.setSelectFirstSource(false);
        controller.fallbackSource(item);
    }

    private List<Site> getSearchableSites() {
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (isPass(site)) sites.add(site);
        return sites;
    }

    private boolean isPass(Site item) {
        if (state.isAutoFallback() && !item.isChangeable()) return false;
        return item.isSearchable();
    }

    private boolean mismatch(Vod item) {
        if (id(host.getVodKey(),host.getVodId()).equals(id(item.getSiteKey(),item.getId()))) return true;
        if (state.hasFailedId(id(item.getSiteKey(),item.getId()))) return true;
        if (state.isAutoFallback()) return !item.getName().equals(state.getSearchKeyword());
        return !item.getName().contains(state.getSearchKeyword());
    }
}
