package com.fongmi.android.tv.ui;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.ui.activity.*;
import com.fongmi.android.tv.ui.source.SourcePage;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.*;
import android.view.*;
import android.widget.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,36},application=App.class,shadows=SourceEntryTest.NativePluginLoader.class)
public class MaintenanceStabilityTest {
 @Test public void sourceInsetsStayOnViewportAfterScrolling(){
  try(var c=Robolectric.buildActivity(SourceStatusActivity.class).setup()){
   var root=(android.view.ViewGroup)c.get().findViewById(android.R.id.content);
   ScrollView scroll=(ScrollView)root.getChildAt(0);
   WindowInsetsCompat insets=new WindowInsetsCompat.Builder().setSystemWindowInsets(androidx.core.graphics.Insets.of(0,40,0,32)).setStableInsets(androidx.core.graphics.Insets.of(0,40,0,32)).setInsets(WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.of(0,40,0,32)).build();
   ViewCompat.dispatchApplyWindowInsets(scroll,insets);
   scroll.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));scroll.layout(0,0,360,640);
   scroll.scrollTo(0,1000);
   assertTrue(scroll.getScrollY()>0);assertEquals(40,scroll.getPaddingTop());assertEquals(32,scroll.getPaddingBottom());assertTrue(scroll.getClipToPadding());
  }
 }
 @Test public void crashDetailsCanBeCopiedWithoutClosingDialog()throws Exception{
  try(var c=Robolectric.buildActivity(CrashActivity.class).setup()){
   var method=CrashActivity.class.getDeclaredMethod("showError");method.setAccessible(true);method.invoke(c.get());
   Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
   Button copy=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);assertNotNull(copy);copy.performClick();
   var clipboard=c.get().getSystemService(android.content.ClipboardManager.class);
   assertTrue(clipboard.hasPrimaryClip());assertEquals(((TextView)dialog.findViewById(android.R.id.message)).getText().toString(),clipboard.getPrimaryClip().getItemAt(0).getText().toString());assertTrue(dialog.isShowing());
  }
 }
 @Test public void staleSavedLineIndexAndImmutableUrlsDoNotCrash(){
  Channel c=Channel.create("test");c.setUrls(java.util.List.of("https://test.invalid/one","https://test.invalid/two"));c.setIndex(50);
  assertEquals("https://test.invalid/two",c.getCurrent());c.setUrls(java.util.List.of("https://test.invalid/one"));assertEquals("https://test.invalid/one",c.getCurrent());
  Channel alternate=Channel.create("test");alternate.setUrls(java.util.List.of("https://test.invalid/backup"));c.addVerifiedLine(alternate);assertEquals("https://test.invalid/backup",c.getCurrent());
 }
 @Test public void realLiveActivityCanOpenAndReportFailureBeforeServiceConnects(){
  com.fongmi.android.tv.api.config.LiveConfig.get().init();
  // Robolectric does not start MediaSessionService; provide a named, unready binding.
  Shadows.shadowOf(App.get()).setComponentNameAndServiceForBindService(new android.content.ComponentName(App.get(),com.fongmi.android.tv.service.PlaybackService.class),new android.os.Binder());
  android.content.Intent intent=new android.content.Intent(App.get(),LiveActivity.class).putExtra("empty",false);
  try(var c=Robolectric.buildActivity(LiveActivity.class,intent).setup()){
   assertFalse(c.get().isPlaybackServiceReady());
   c.get().stopPlaybackForRefresh();c.get().resetPlaybackForError("test unavailable");
  }
 }

}
