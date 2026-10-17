package com.fongmi.android.tv.ui;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.source.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import static org.junit.Assert.*;

/** Audit reproductions. Assertions express required behavior, not current implementation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=App.class,shadows={SourceEntryTest.NativePluginLoader.class,ReviewRegressionTest.Probe.class})
public class ReviewRegressionTest {
 @Implements(value=LiveMediaProbe.class,isInAndroidSdk=false)
 public static class Probe {
   static final List<String> tested=new CopyOnWriteArrayList<>();
   @Implementation protected static boolean check(Channel c){tested.add(c.getCurrent());return c.getCurrent().contains("working");}
 }
 private static void field(String name,Object value)throws Exception{Field f=LiveHealth.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 private static Object field(String name)throws Exception{Field f=LiveHealth.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 @Before public void reset()throws Exception{
   LiveHealth.stopBackground();
   App.get().getSharedPreferences("live-health",0).edit().clear().commit();Probe.tested.clear();
   field("catalog",Collections.emptyList());field("catalogAt",0L);field("warming",null);field("updating",null);
   ((Map<?,?>)field("failed")).clear();MaintenanceSettings.enabled(true);
 }
 private Channel channel(String name,String... urls){Channel c=Channel.create(name);c.setUrls(new ArrayList<>(Arrays.asList(urls)));return c;}
 private Channel prepare(Channel c)throws Exception{
   Channel[] out={null};LiveHealth.prepare(c,Collections.emptySet(),v->out[0]=v).get(3,TimeUnit.SECONDS);
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();return out[0];
 }
 @Test public void aPlayableFourthCandidateMustNotBeReportedAsUnavailable()throws Exception{
   Channel c=channel("fourth-candidate","https://a.invalid/broken1","https://a.invalid/broken2","https://a.invalid/broken3","https://a.invalid/working4");
   Channel result=prepare(c);
   assertEquals("all four candidates should be tested",4,Probe.tested.size());
   assertNotNull("4th candidate is usable but never tested; visited="+Probe.tested,result);
 }
 @Test public void genericNameMustNotReuseAnotherStationWithDifferentTvgId()throws Exception{
   Channel a=channel("新闻","https://station-a.invalid/working");a.setTvgId("station-a.cn");
   Channel b=channel("新闻","https://station-b.invalid/working");b.setTvgId("station-b.cn");
   prepare(a);
   assertNull("Different TVG IDs sharing a display name must not share a verified cache entry",LiveHealth.known(b));
 }
 @Test public void automaticWarmupMustResumeAfterForegroundCancellation()throws Exception{
   var network=App.get().getSystemService(android.net.ConnectivityManager.class);
   Shadows.shadowOf(network).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
       android.net.NetworkInfo.DetailedState.CONNECTED,android.net.ConnectivityManager.TYPE_WIFI,0,true,true));
   assertFalse(network.isActiveNetworkMetered());
   CompletableFuture<Void> previous=new CompletableFuture<>();field("warming",previous);
   field("catalogAt",System.currentTimeMillis());
   field("catalog",List.of(channel("background","https://background.invalid/working")));
   prepare(channel("foreground","https://foreground.invalid/working"));
   assertTrue(previous.isCancelled());
   LiveHealth.schedule();
   assertNotSame("Fresh catalog must not suppress resuming canceled health probes",previous,field("warming"));
 }
 @Test public void genericNamesWithoutTvgIdsRemainIsolatedEvenInTheCatalog()throws Exception{
   Channel a=channel("新闻","https://a.invalid/working");
   Channel b=channel("新闻","https://b.invalid/broken");
   prepare(a);field("catalog",List.of(a));
   assertNull(LiveHealth.known(b));assertNull(prepare(b));
 }
 @Test public void channelCopiesPreserveExplicitIdentityWithoutInventingOne() {
   Channel explicit=channel("新闻","https://a.invalid/working");explicit.setTvgId("station-a.cn");
   assertEquals("station-a.cn",explicit.transport(explicit.getCurrent()).getExplicitTvgId());
   Channel unnamed=channel("新闻","https://b.invalid/working");
   assertEquals("",Channel.create(unnamed).getExplicitTvgId());
 }
 @Test public void preUpgradeNameOnlyCacheCannotBeUsedAsAValidatedLine() {
   Channel c=channel("新闻","https://a.invalid/working");
   App.get().getSharedPreferences("live-health",0).edit()
     .putString("channel:新闻",App.gson().toJson(c)).putLong("time:新闻",System.currentTimeMillis()).commit();
   assertNull(LiveHealth.known(c));
 }
 @Test public void disablingMaintenanceCancelsBothBackgroundTasks()throws Exception{
   CompletableFuture<Void> warm=new CompletableFuture<>(),update=new CompletableFuture<>();
   field("warming",warm);field("updating",update);MaintenanceSettings.enabled(false);
   assertTrue(warm.isCancelled());assertTrue(update.isCancelled());
 }

}
