package com.fongmi.android.tv.api.config;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=App.class,shadows=com.fongmi.android.tv.ui.SourceEntryTest.NativePluginLoader.class)
public class ConfigCompletionTest {
 static class Loader extends BaseConfig {
  protected String getTag(){return "TestConfig";}
  protected Config defaultConfig(){return Config.vod();}
  protected void load(Config config) throws Throwable {throw new java.net.SocketTimeoutException("Read timed out");}
  protected boolean isLoaded(){return false;}
  protected void postEvent(){}
  void run(Callback c){loadConfig(0,Config.objectFrom("{\"type\":0,\"url\":\"https://example.com/config\",\"name\":\"Test\"}"),c);}
 }
 @Test public void networkTimeoutAlwaysFinishesCurrentLoad() {
   final boolean[] finished={false};
   new Loader().run(new Callback(){public void error(String message){finished[0]=true;}});
   org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   assertTrue("timeout must dismiss loading UI",finished[0]);
 }
}
