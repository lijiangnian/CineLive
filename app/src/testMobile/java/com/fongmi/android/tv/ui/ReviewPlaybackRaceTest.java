package com.fongmi.android.tv.ui;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.source.LiveHealth;
import com.fongmi.android.tv.playback.live.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=App.class,shadows={SourceEntryTest.NativePluginLoader.class,ReviewPlaybackRaceTest.DelayedHealth.class})
public class ReviewPlaybackRaceTest {
 @Implements(value=LiveHealth.class,isInAndroidSdk=false)
 public static class DelayedHealth {
  static Consumer<Channel> pending;
  @Implementation protected static boolean applies(Channel c){return true;}
  @Implementation protected static Future<?> prepare(Channel c,Set<String> excluded,Consumer<Channel> result){pending=result;return new CompletableFuture<>();}
 }
 @Test public void aLateLiveProbeMustNotReplaceUserSelectedCatchup() {
  List<LivePlayRequest> requests=new ArrayList<>();
  LivePlaybackHost host=(LivePlaybackHost)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{LivePlaybackHost.class},(obj,method,args)->{
   if(method.getName().equals("requestUrl"))requests.add((LivePlayRequest)args[0]);
   if(method.getName().equals("getZoneId"))return java.time.ZoneId.systemDefault();
   if(method.getReturnType()==boolean.class)return method.getName().equals("isPlaybackServiceReady");
   if(method.getReturnType()==int.class)return 0;
   if(method.getReturnType()==long.class)return 0L;
   return null;
  });
  Channel c=Channel.create("test-channel");c.setUrls(new ArrayList<>(List.of("https://example.invalid/live")));c.group(Group.create());
  LivePlaybackController controller=new LivePlaybackController(host,new LivePlaybackState());
  try {
   controller.selectChannel(c);assertTrue(requests.isEmpty());
   EpgData program=new EpgData();program.setSelected(true);program.setTitle("earlier program");
   assertTrue(controller.selectEpg(program));assertTrue(requests.get(0).isCatchup());
   Channel validated=Channel.create("test-channel");validated.setUrls(new ArrayList<>(List.of("https://example.invalid/backup")));
   Consumer<Channel> late=DelayedHealth.pending;
   late.accept(validated);
   assertTrue("Late live validation must not switch the chosen catchup back to live",requests.get(requests.size()-1).isCatchup());
   assertEquals(1,requests.size());
   // A catchup resolver error must also preserve catchup intent for fallback decisions.
   try {
    var failure=LivePlaybackController.class.getDeclaredMethod("failPlayback",String.class);
    failure.setAccessible(true);failure.invoke(controller,"catchup failed");
   }catch(ReflectiveOperationException e){throw new AssertionError(e);}
   assertSame("Catchup failure must not start another live verification",late,DelayedHealth.pending);
  } finally {controller.release();}
 }
}
