package com.fongmi.android.tv.ui;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.playback.live.*;
import com.fongmi.android.tv.source.LiveHealth;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,36},application=App.class,shadows={SourceEntryTest.NativePluginLoader.class,LiveServiceLifecycleTest.Health.class})
public class LiveServiceLifecycleTest {
 @Implements(value=LiveHealth.class,isInAndroidSdk=false)
 public static class Health {
  static List<Consumer<Channel>> callbacks=new ArrayList<>();
  @Implementation protected static boolean applies(Channel c){return true;}
  @Implementation protected static Future<?> prepare(Channel c,Set<String> exclude,Consumer<Channel> callback){callbacks.add(callback);return new CompletableFuture<>();}
 }
 boolean connected; int stops; List<LivePlayRequest> requests; List<String> errors; LivePlaybackController controller;
 @Before public void setup(){
  Health.callbacks.clear();requests=new ArrayList<>();errors=new ArrayList<>();connected=false;stops=0;
  LivePlaybackHost host=(LivePlaybackHost)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{LivePlaybackHost.class},(obj,m,args)->{
   if(m.getName().equals("isPlaybackServiceReady"))return connected;
   if(m.getName().equals("stopPlaybackForRefresh")){
    // Matches the null PlaybackService failure shown on the user's phone.
    if(!connected)throw new NullPointerException("PlaybackService.player() before service connection");stops++;
   }
   if(m.getName().equals("requestUrl"))requests.add((LivePlayRequest)args[0]);
   if(m.getName().equals("resetPlaybackForError"))errors.add((String)args[0]);
   if(m.getName().equals("getZoneId"))return java.time.ZoneId.systemDefault();
   if(m.getReturnType()==boolean.class)return false;
   if(m.getReturnType()==int.class)return 0;
   if(m.getReturnType()==long.class)return 0L;
   return null;
  });
  controller=new LivePlaybackController(host,new LivePlaybackState());
 }
 @After public void teardown(){controller.release();}
 private Channel channel(String id){Channel c=Channel.create(id);c.setUrls(new ArrayList<>(List.of("https://test.invalid/"+id)));c.group(Group.create());return c;}
 @Test public void fastPlaylistMustWaitForSlowServiceBinding(){
  controller.selectChannel(channel("one"));
  assertEquals(0,stops);assertTrue(Health.callbacks.isEmpty());
  connected=true;controller.onPlaybackServiceReady();
  assertEquals(1,Health.callbacks.size());assertEquals(1,stops);
 }
 @Test public void repeatedSelectionBeforeServiceBindingKeepsLatestChannel(){
  controller.selectChannel(channel("one"));controller.selectChannel(channel("two"));
  assertTrue(Health.callbacks.isEmpty());connected=true;controller.onPlaybackServiceReady();
  Health.callbacks.get(0).accept(channel("two"));assertEquals("two",requests.get(0).getChannel().getName());
 }
 @Test public void releasePreventsLateServiceConnectionFromStartingPlayback(){
  connected=true;controller.selectChannel(channel("one"));controller.release();int before=stops;
  Health.callbacks.get(0).accept(channel("one"));controller.onPlaybackServiceReady();
  assertTrue(requests.isEmpty());assertEquals(before,stops);
 }
 @Test public void catchupChosenBeforeBindingIsNotLost(){
  controller.selectChannel(channel("one"));EpgData epg=new EpgData();epg.setSelected(true);
  controller.selectEpg(epg);connected=true;controller.onPlaybackServiceReady();
  assertEquals(1,requests.size());assertTrue(requests.get(0).isCatchup());assertTrue(Health.callbacks.isEmpty());
 }
 @Test public void disconnectDuringProbeDefersPlaybackAndIgnoresOldCallback(){
  connected=true;controller.selectChannel(channel("one"));Consumer<Channel> old=Health.callbacks.get(0);
  connected=false;controller.onPlaybackServiceLost();old.accept(channel("one"));assertTrue(requests.isEmpty());
  connected=true;controller.onPlaybackServiceReady();assertEquals(2,Health.callbacks.size());
  Health.callbacks.get(1).accept(channel("one"));assertEquals(1,requests.size());
 }
 @Test public void unsupportedProbeFallsBackToOriginalPlayerWithoutInventingVerifiedBackup(){
  Channel original=channel("one");original.setUrls(new ArrayList<>(List.of("https://test.invalid/one","https://test.invalid/two")));
  connected=true;controller.selectChannel(original);Health.callbacks.get(0).accept(null);
  assertEquals(1,requests.size());assertEquals(2,original.getUrls().size());
  controller.playbackError("failed");Health.callbacks.get(1).accept(null);
  assertEquals(2,requests.size());assertEquals("https://test.invalid/two",requests.get(1).getChannel().getCurrent());
  controller.playbackError("failed");Health.callbacks.get(2).accept(null);assertEquals(2,requests.size());assertFalse(errors.isEmpty());
 }
 @Test public void bindingTimeoutEndsProgressInsteadOfCrashing(){
  controller.selectChannel(channel("one"));
  org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(16));
  assertEquals(1,errors.size());assertEquals(0,stops);
 }

 @Test public void evenVerifiedCandidatesStopAfterEightActualPlaybackFailures(){
  connected=true;controller.selectChannel(channel("one"));
  for(int i=0;i<8;i++){
   Health.callbacks.get(i).accept(channel("candidate"+i));
   controller.playbackError("real playback failed after probe");
  }
  assertEquals(8,requests.size());assertEquals(8,Health.callbacks.size());
  assertTrue(errors.get(errors.size()-1).contains("暂不可用"));
 }

}
