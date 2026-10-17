package com.fongmi.android.tv.ui;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.source.*;
import com.fongmi.android.tv.playback.live.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=36,application=App.class,shadows={SourceEntryTest.NativePluginLoader.class,AutomaticMaintenanceIntegrationTest.Decoder.class})
public class AutomaticMaintenanceIntegrationTest {
 @Implements(value=LiveMediaProbe.class,isInAndroidSdk=false)
 public static class Decoder {
  @Implementation protected static boolean check(Channel c){return !c.getCurrent().contains("broken");}
 }
 private void field(String name,Object value)throws Exception{Field f=LiveHealth.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 @Before public void setup()throws Exception{
  LiveHealth.stopBackground();App.get().getSharedPreferences("live-health",0).edit().clear().commit();
  field("catalog",Collections.emptyList());field("activeChannels",Collections.emptyList());field("catalogAt",System.currentTimeMillis());
 }
 private Channel channel(String url){Channel c=Channel.create("新闻");c.setTvgId("news.cn");c.setUrls(List.of(url));c.group(Group.create());return c;}
 private void finishProbe(LivePlaybackController c)throws Exception{
  Field f=LivePlaybackController.class.getDeclaredField("verification");f.setAccessible(true);
  ((Future<?>)f.get(c)).get(3,TimeUnit.SECONDS);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
 }
 @Test public void realSelectorAndControllerAutomaticallyReplaceFailedLineWithVerifiedSameChannel()throws Exception{
  Channel first=channel("https://one.invalid/live");first.setHeader(Map.of("Authorization","provider-one"));
  Channel second=channel("https://two.invalid/live");second.setHeader(Map.of("Referer","https://two.invalid/"));
  field("catalog",List.of(second));List<String> urls=new ArrayList<>();List<Map<String,String>> headers=new ArrayList<>();
  LivePlaybackHost host=(LivePlaybackHost)Proxy.newProxyInstance(getClass().getClassLoader(),new java.lang.Class<?>[]{LivePlaybackHost.class},(o,m,args)->{
   if(m.getName().equals("isPlaybackServiceReady"))return true;
   if(m.getName().equals("getZoneId"))return java.time.ZoneId.systemDefault();
   if(m.getName().equals("requestUrl")){Channel c=((LivePlayRequest)args[0]).getChannel();urls.add(c.getCurrent());headers.add(new HashMap<>(c.getHeaders()));}
   if(m.getReturnType()==boolean.class)return false;
   if(m.getReturnType()==int.class)return 0;
   if(m.getReturnType()==long.class)return 0L;
   return null;
  });
  LivePlaybackController controller=new LivePlaybackController(host,new LivePlaybackState());
  try{
   controller.selectChannel(first);finishProbe(controller);controller.playbackError("connection lost");finishProbe(controller);
   assertEquals(List.of("https://one.invalid/live","https://two.invalid/live"),urls);
   assertEquals("provider-one",headers.get(0).get("Authorization"));assertFalse(headers.get(1).containsKey("Authorization"));
   assertEquals("https://two.invalid/",headers.get(1).get("Referer"));
  }finally{controller.release();}
 }
 @Test public void changedNetworkCannotReusePreviousVerifiedResult()throws Exception{
  Channel c=channel("https://network.invalid/live");LiveHealth.prepare(c,Set.of(),ignored->{}).get(3,TimeUnit.SECONDS);
  assertNotNull(LiveHealth.known(c));
  var prefs=App.get().getSharedPreferences("live-health",0);
  for(String key:prefs.getAll().keySet())if(key.startsWith("network:"))prefs.edit().putString(key,"different-network").commit();
  assertNull(LiveHealth.known(c));
 }
 @Test public void actualHttpSubscriptionFailureSelectsMirrorAndAllFailedKeepsCache()throws Exception{
  var server=new java.net.ServerSocket(0,20,java.net.InetAddress.getByName("127.0.0.1"));
  var unavailable=new java.util.concurrent.atomic.AtomicBoolean(false);
  var worker=new Thread(()->{
   while(!server.isClosed())try(var socket=server.accept()){
    socket.setSoTimeout(3000);
    var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
    String request=reader.readLine(),line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
    boolean backup=request!=null&&request.contains("/backup/config.json");
    boolean failed=backup&&unavailable.get();
    String body=backup?"{\"sites\":[{\"key\":\"one\",\"name\":\"test\",\"type\":3,\"api\":\"csp_test\"}],\"spider\":\"./plugin.jar\"}":"<html>challenge</html>";
    byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var output=socket.getOutputStream();
    output.write(("HTTP/1.1 "+(failed?"503 Unavailable":"200 OK")+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    output.write(bytes);output.flush();
   }catch(java.io.IOException e){if(!server.isClosed())throw new RuntimeException(e);}
  });worker.setDaemon(true);worker.start();
  try{
   String base="http://127.0.0.1:"+server.getLocalPort(),primary=base+"/bad";
   String key=SubscriptionCache.key(primary,SubscriptionCache.Kind.VOD);MaintenanceSettings.save(key,base+"/backup/config.json");
   SourceStore.Document first=SubscriptionCache.refreshForMaintenance(primary,"integration",SubscriptionCache.Kind.VOD,true);
   assertEquals(1,first.entries);assertEquals(1,MaintenanceSettings.used(key));assertTrue(first.text.contains(base+"/backup/plugin.jar"));
   unavailable.set(true);SourceStore.Document failed=SubscriptionCache.refreshForMaintenance(primary,"integration",SubscriptionCache.Kind.VOD,true);
   assertTrue(failed.fallback);assertEquals(first.text,failed.text);assertEquals(1,failed.failures);
  }finally{server.close();worker.join(3000);}
 }
}
