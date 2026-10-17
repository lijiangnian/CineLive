package com.fongmi.android.tv.ui;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.source.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=App.class,shadows=SourceEntryTest.NativePluginLoader.class)
public class MaintenanceFlowTest {
 @Test public void privateRouteOnlyAcceptsExactApprovedConfiguration() {
   String good="https://iptv-org.github.io/iptv/languages/zho.m3u";
   assertTrue(MaintenanceTransport.eligible(good));
   for(String bad:new String[]{good+"?key=secret",good+"/segment.ts","https://raw.githubusercontent.com/custom/plugin.jar","https://example.com/list.m3u","http://iptv-org.github.io/iptv/languages/zho.m3u"})assertFalse(bad,MaintenanceTransport.eligible(bad));
 }
 @Test public void channelMatchingDoesNotConfuseRegionalOrPlusChannels() {
   assertTrue(ChannelIdentity.matches("CCTV-1 (720p)","CCTV1综合"));
   assertFalse(ChannelIdentity.matches("CCTV4 Europe","CCTV4 America"));
   assertFalse(ChannelIdentity.matches("CCTV5+","CCTV5"));
   assertFalse(ChannelIdentity.matches("CGTN英语","CGTN法语"));
 }
 @Test public void verifiedBackupPreservesOriginalLinesAndSeparatesCredentials() {
   Channel original=Channel.create("News");original.setUrls(new java.util.ArrayList<>(java.util.List.of("https://one.example/live","https://two.example/live")));
   original.setHeader(java.util.Map.of("Authorization","original"));
   Channel alternate=Channel.create("News");alternate.setUrls(new java.util.ArrayList<>(java.util.List.of("https://three.example/live")));
   alternate.setHeader(java.util.Map.of("Referer","https://three.example/"));
   original.addVerifiedLine(alternate);
   assertEquals(3,original.getUrls().size());assertEquals("https://three.example/live",original.getCurrent());
   assertFalse(original.getHeaders().containsKey("Authorization"));
   original.setIndex(0);assertEquals("original",original.getHeaders().get("Authorization"));
   assertFalse(original.getHeaders().containsKey("Referer"));
 }
 @Test public void sourceTabsHaveGapAndEnoughHeightForLargeText() {
   try(var controller=Robolectric.buildActivity(com.fongmi.android.tv.ui.activity.SourceCatalogActivity.class).setup()) {
     var a=controller.get();var root=a.findViewById(android.R.id.content);
     var left=(android.widget.TextView)a.findViewById(com.fongmi.android.tv.R.id.catalogVod);
     var right=(android.widget.TextView)a.findViewById(com.fongmi.android.tv.R.id.catalogLive);
     left.setTextSize(26);right.setTextSize(26);
     root.measure(android.view.View.MeasureSpec.makeMeasureSpec(360,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(800,1073741824));root.layout(0,0,360,800);
     assertTrue(left.getRight()<right.getLeft());assertTrue(left.getHeight()>=left.getLayout().getHeight()+left.getPaddingTop()+left.getPaddingBottom());
   }
 }
}
