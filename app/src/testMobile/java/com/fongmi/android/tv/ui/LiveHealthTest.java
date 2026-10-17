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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=App.class,shadows={SourceEntryTest.NativePluginLoader.class,LiveHealthTest.Probe.class})
public class LiveHealthTest {
 @Implements(value=LiveMediaProbe.class,isInAndroidSdk=false)
 public static class Probe {
   static final List<String> tested=new CopyOnWriteArrayList<>();
   @Implementation protected static boolean check(Channel c){tested.add(c.getCurrent());return c.getCurrent().contains("working");}
 }
 @Before public void reset(){App.get().getSharedPreferences("live-health",0).edit().clear().commit();Probe.tested.clear();}
 private Channel channel(String name,String... urls){Channel c=Channel.create(name);c.setUrls(new ArrayList<>(Arrays.asList(urls)));return c;}
 private Channel prepare(Channel c) throws Exception {
   Channel[] out={null};LiveHealth.prepare(c,Collections.emptySet(),v->out[0]=v).get(3,TimeUnit.SECONDS);
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();return out[0];
 }
 @Test public void unverifiedFailuresNeverBecomeBackups() throws Exception {
   Channel c=channel("test-a","https://test-a.invalid/broken","https://test-a.invalid/working");
   Channel ready=prepare(c);assertNotNull(ready);assertTrue(ready.getCurrent().contains("working"));assertEquals(2,Probe.tested.size());
   assertEquals(ready.getCurrent(),LiveHealth.known(c).getCurrent());
   Probe.tested.clear();assertNotNull(prepare(c));assertTrue("fresh verified result should be preferred",Probe.tested.isEmpty());
 }
 @Test public void allFailedReturnsNoPlayableCandidate() throws Exception {
   Channel c=channel("test-b","https://test-b.invalid/broken");assertNull(prepare(c));assertNull(LiveHealth.known(c));
 }
 @Test public void failedRememberedLineIsRemovedAndAnotherIsVerified() throws Exception {
   Channel c=channel("test-c","https://test-c.invalid/working-one","https://test-c.invalid/working-two");
   Channel first=prepare(c);LiveHealth.failed(first);assertNull(LiveHealth.known(c));
   Channel second=prepare(c);assertNotNull(second);assertNotEquals(first.getCurrent(),second.getCurrent());
 }
}
