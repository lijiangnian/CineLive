package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.*;
import com.fongmi.android.tv.playback.PlaybackResult;
import com.fongmi.android.tv.ui.SourceEntryTest;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=App.class,shadows=SourceEntryTest.NativePluginLoader.class)
public class VodFailoverTest {
 VodPlaybackState state;VodPlaybackController controller;VodFallbackPolicy policy;
 List<String> switches;List<VodPlayRequest> requests;int searches;String key="a",id="1";
 @Before public void setup(){
  state=new VodPlaybackState();switches=new ArrayList<>();requests=new ArrayList<>();searches=0;
  VodPlaybackHost host=(VodPlaybackHost)Proxy.newProxyInstance(getClass().getClassLoader(),new java.lang.Class<?>[]{VodPlaybackHost.class},(o,m,args)->{
   switch(m.getName()){
    case "getVodKey":return key;
    case "getVodId":return id;
    case "getVodName":return "测试剧";
    case "getHistoryKey":return "test@@@"+key+"@@@"+id;
    case "getVodMark":case "getVodPic":return "";
    case "isSiteChangeable":return true;
    case "requestSearch":searches++;break;
    case "prepareSource":Vod v=(Vod)args[0];key=v.getSiteKey();id=v.getId();switches.add(key);break;
    case "requestPlayer":requests.add((VodPlayRequest)args[0]);break;
   }
   if(m.getReturnType()==boolean.class)return false;
   if(m.getReturnType()==long.class)return 0L;
   if(m.getReturnType()==int.class)return 0;
   return null;
  });
  controller=new VodPlaybackController(host,state);policy=new VodFallbackPolicy(controller,state,host);
  History history=new History();history.setVodRemarks("第02集");state.setHistory(history);
 }
 @After public void teardown(){controller.release();}
 private Flag flag(String name,String... episodes){Flag f=Flag.create(name);for(String e:episodes)f.getEpisodes().add(Episode.create(e,"https://invalid.test/"+name+"/"+e));return f;}
 private Vod vod(String site,String id,String year){
  Vod v=App.gson().fromJson("{\"vod_name\":\"测试剧\",\"vod_year\":\""+year+"\",\"type_name\":\"电视剧\"}",Vod.class);
  v.setId(id);Site s=new Site();s.setKey(site);v.setSite(s);return v;
 }
 @Test public void automaticLineSwitchSkipsWrongEpisodeAndSelectsSameEpisode(){
  Flag first=flag("first","第02集"),wrong=flag("wrong","第01集"),right=flag("right","01","02");
  first.setSelected(first);first.toggle(true,first.getEpisodes().get(0));state.setFlags(List.of(first,wrong,right));
  state.getHistory().setPosition(120_000);policy.playbackError();assertEquals(120_000,state.getHistory().getPosition());assertEquals(1,requests.size());assertEquals("right",requests.get(0).getFlag());assertTrue(requests.get(0).getId().endsWith("/02"));
 }
 @Test public void sameNumericIdAtAnotherSiteRemainsCandidate(){
  policy.search("测试剧",true);Result result=new Result();result.setList(List.of(vod("b","1","2020")));policy.onSearchResult(result);
  assertEquals(List.of("b"),switches);
 }
 @Test public void exhaustedSearchDoesNotLoopForever(){
  for(int i=0;i<20;i++){policy.playbackError();policy.onSearchResult(new Result());}
  assertEquals(1,searches);
 }
 @Test public void remakeAndMissingYearCannotPassAutomaticIdentityCheck(){
  assertFalse(VodIdentity.sameTitle(vod("a","1","2020"),vod("b","2","2024")));
  assertFalse(VodIdentity.sameTitle(vod("a","1","2020"),vod("b","2","")));
  assertTrue(VodIdentity.sameTitle(vod("a","1","2020"),vod("b","2","2020")));
  assertNull(VodIdentity.episode(flag("wrong","第01集"),"第02集",false));
 }
 @Test public void emptyResolvedUrlTriggersAutomaticNextLine(){
  Flag one=flag("one","第02集"),two=flag("two","第02集");one.setSelected(one);one.toggle(true,one.getEpisodes().get(0));state.setFlags(List.of(one,two));
  VodPlayRequest req=VodPlayRequest.create(key,one,one.getEpisodes().get(0));state.setPendingRequest(req);
  controller.onPlaybackResult(new PlaybackResult<>(req,new Result()));
  assertEquals(1,requests.size());assertEquals("two",requests.get(0).getFlag());
 }
 @Test public void sustainedBufferingTriggersFallbackButReadyCancelsIt(){
  Flag one=flag("one","第02集"),two=flag("two","第02集");one.setSelected(one);one.toggle(true,one.getEpisodes().get(0));state.setFlags(List.of(one,two));
  controller.buffering();controller.ready();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31));assertTrue(requests.isEmpty());
  controller.buffering();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31));assertEquals(1,requests.size());
 }
 @Test public void resolvedPlaybackErrorCannotReplayAnOlderRequestForSameEpisode(){
  Flag one=flag("one","第02集");one.setSelected(one);state.setFlags(List.of(one));
  VodPlayRequest old=VodPlayRequest.create(key,one,one.getEpisodes().get(0)), latest=VodPlayRequest.create(key,one,one.getEpisodes().get(0));
  state.setPendingRequest(latest);controller.onPlaybackResult(new PlaybackResult<>(old,new Result()));
  assertSame(latest,state.getPendingRequest());assertEquals(0,searches);
 }
 @Test public void crossSiteDetailMustConfirmMovieBeforePlayback(){
  com.fongmi.android.tv.api.config.VodConfig.get().init();
  Vod expected=vod("a","1","2020");expected.setFlags(List.of(flag("one","第02集")));
  Result first=new Result();first.setList(List.of(expected));controller.onDetailResult(new VodDetailResult("a","1",first));
  int before=requests.size();controller.playbackError("failed");
  Vod candidate=vod("b","1","");Result search=new Result();search.setList(List.of(candidate));controller.onSearchResult(search);
  assertEquals(List.of("b"),switches);
  Vod remake=vod("b","1","2024");remake.setFlags(List.of(flag("wrong","第02集")));Result detail=new Result();detail.setList(List.of(remake));
  controller.onDetailResult(new VodDetailResult("b","1",detail));assertEquals(before,requests.size());
 }

 @Test public void laterSearchBatchDoesNotDiscardQueuedCandidates(){
  policy.search("测试剧",true);
  policy.onSearchResult(new Result());assertTrue(state.isSelectFirstSource());
  Result first=new Result();first.setList(List.of(vod("b","1","2020"),vod("c","1","2020")));policy.onSearchResult(first);
  Result second=new Result();second.setList(List.of(vod("d","1","2020"),vod("c","1","2020")));policy.onSearchResult(second);
  policy.onSearchResult(new Result());
  assertEquals(List.of("c","d"),state.getSources().stream().map(Vod::getSiteKey).toList());
 }
 @Test public void explicitRetryCanSearchAgainAfterAutomaticBudgetExhausted(){
  policy.playbackError();policy.playbackError();assertEquals(1,searches);
  policy.manualSwitchSource();assertEquals(2,searches);
 }

}
