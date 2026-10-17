package com.fongmi.android.tv.ui;

import android.os.Bundle;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.ui.fragment.SettingFragment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.Shadows;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=App.class, shadows=SourceEntryTest.NativePluginLoader.class)
public class SourceEntryTest {
    // ARM Python/JS plugin runtimes cannot execute in a host JVM. UI and config code remain real.
    @org.robolectric.annotation.Implements(value=com.fongmi.android.tv.api.loader.BaseLoader.class, isInAndroidSdk=false)
    public static class NativePluginLoader {
        @org.robolectric.annotation.Implementation protected void __constructor__() {}
        @org.robolectric.annotation.Implementation protected void clear() {}
        @org.robolectric.annotation.Implementation protected void clearNow() {}
    }
    public static class CapturingSettings extends SettingFragment {
        com.fongmi.android.tv.bean.Config selected;
        @Override public void setConfig(com.fongmi.android.tv.bean.Config config) { selected = config; }
    }
    public static class Host extends AppCompatActivity {
        protected void onCreate(Bundle b) {
            setTheme(R.style.Theme_App);
            super.onCreate(b);
            FrameLayout frame=new FrameLayout(this);frame.setId(123456);setContentView(frame);
            VodConfig.get().init();LiveConfig.get().init();WallConfig.get().init();
            getSupportFragmentManager().beginTransaction().add(frame.getId(), new CapturingSettings(),"settings").commitNow();
        }
    }
    @Test public void builtInSourcesAreAvailableWithout115OrNetwork() throws Exception {
        var context=App.get();
        var vod=com.fongmi.android.tv.source.BuiltinSources.read(context,0);
        var live=com.fongmi.android.tv.source.BuiltinSources.read(context,1);
        assertTrue(vod.size()>5); assertTrue(live.size()>1);
        assertTrue(vod.stream().anyMatch(row->row.name.contains("肥猫")));
        assertTrue(vod.stream().anyMatch(row->row.name.contains("饭太硬")));
    }
    @Test public void catalogTabsAndCustomAddressRouteAreClickable() {
        try(var controller=Robolectric.buildActivity(com.fongmi.android.tv.ui.activity.SourceCatalogActivity.class).setup()) {
            var activity=controller.get();
            assertTrue(activity.findViewById(R.id.catalogLive).performClick());
            TextViewClick.clickContaining(activity.findViewById(android.R.id.content),"填写自己的源地址");
            assertEquals(1,Shadows.shadowOf(activity).getNextStartedActivity().getIntExtra("configure_source",-1));
        }
    }
    private static class TextViewClick {
        static boolean clickContaining(android.view.View view,String text) {
            if(view instanceof android.widget.TextView tv && tv.getText().toString().contains(text))return tv.performClick();
            if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++)if(clickContaining(group.getChildAt(i),text))return true;
            return false;
        }
    }
    @Test public void sourceCatalogCanBeOpenedFromSettings() {
        try(var controller=Robolectric.buildActivity(Host.class).setup()) {
            var activity=controller.get();
            assertTrue(activity.findViewById(R.id.sourceCatalog).performClick());
            assertEquals(com.fongmi.android.tv.ui.activity.SourceCatalogActivity.class.getName(),
                    Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            assertFalse(activity.findViewById(R.id.sourceMaintenance) instanceof android.widget.Button);
        }
    }
    @Test @Config(sdk=35) public void bottomLabelsFitAboveSystemBarInPortraitAndLandscape() {
        try(var controller=Robolectric.buildActivity(com.fongmi.android.tv.ui.activity.HomeActivity.class).setup()) {
            var activity=controller.get();
            var root=activity.findViewById(R.id.homeRoot);
            var navigation=(com.google.android.material.bottomnavigation.BottomNavigationView)activity.findViewById(R.id.navigation);
            for(int[] bounds:new int[][]{{360,800,24,24,0},{360,800,24,48,0},{800,360,24,0,40}}) {
                var inset=new androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.of(0,bounds[2],bounds[4],bounds[3])).build();
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,inset);
                float density=activity.getResources().getDisplayMetrics().density;
                int width=(int)(bounds[0]*density),height=(int)(bounds[1]*density);
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY));
                root.layout(0,0,width,height);
                assertEquals(bounds[3],root.getPaddingBottom());
                assertEquals(bounds[4],root.getPaddingRight());
                assertTrue(navigation.getBottom()<=height-bounds[3]);
                assertEquals("navigation must not apply a second system inset",0,navigation.getPaddingBottom());
                checkTextBounds(navigation,navigation);
            }
        }
    }
    private static void checkTextBounds(android.view.View view,android.view.ViewGroup navigation) {
        if(view instanceof android.widget.TextView text && view.getVisibility()==android.view.View.VISIBLE && !text.getText().toString().isEmpty()) {
            android.graphics.Rect rect=new android.graphics.Rect(0,0,text.getWidth(),text.getHeight());
            navigation.offsetDescendantRectToMyCoords(text,rect);
            assertTrue("label top clipped",rect.top>=0);
            assertTrue("label bottom clipped",rect.bottom<=navigation.getHeight());
            assertTrue("label left clipped",rect.left>=0);
            assertTrue("label right clipped",rect.right<=navigation.getWidth());
        }
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++)checkTextBounds(group.getChildAt(i),navigation);
    }
    @Test public void freshConfigHasSafeEmptyUrl() {
        assertEquals("", com.fongmi.android.tv.bean.Config.create(0).getUrl());
        assertEquals("", com.fongmi.android.tv.bean.Config.create(1).getUrl());
    }
    @Test public void emptyMaintenanceFinishesAndCanOpenPrimarySource() throws Exception {
        try(var controller=Robolectric.buildActivity(com.fongmi.android.tv.ui.activity.SourceStatusActivity.class).setup()) {
            var activity=controller.get();
            android.widget.Button refresh=null, setLive=null;
            java.util.ArrayDeque<android.view.View> views=new java.util.ArrayDeque<>();
            views.add(activity.findViewById(android.R.id.content));
            while(!views.isEmpty()) {
                var view=views.remove();
                if(view instanceof android.widget.Button button) {
                    if(button.getText().toString().equals(activity.getString(R.string.source_check))) refresh=button;
                    if(button.getText().toString().equals("设置直播源")) setLive=button;
                }
                if(view instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) views.add(group.getChildAt(i));
            }
            assertNotNull(refresh); assertNotNull(setLive);
            var field=activity.getClass().getDeclaredField("task");
            field.setAccessible(true);
            ((java.util.concurrent.Future<?>)field.get(activity)).get(15,java.util.concurrent.TimeUnit.SECONDS);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("empty subscription must not leave maintenance stuck",refresh.isEnabled());
            setLive.performClick();
            assertEquals(1,Shadows.shadowOf(activity).getNextStartedActivity().getIntExtra("configure_source",-1));
        }
    }
    @Test public void liveSourceCanBeEnteredAndSubmitted() {
        try(var controller=Robolectric.buildActivity(Host.class).setup()) {
            Host activity=controller.get();
            activity.findViewById(R.id.live).performClick();
            var settings=(CapturingSettings)activity.getSupportFragmentManager().findFragmentByTag("settings");
            settings.getChildFragmentManager().executePendingTransactions();
            var dialog=(AlertDialog)ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            ((android.widget.EditText)dialog.findViewById(R.id.url)).setText("https://example.com/live.m3u");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNotNull(settings.selected);
            assertEquals(1,settings.selected.getType());
            assertEquals("https://example.com/live.m3u",settings.selected.getUrl());
        }
    }
    @Test public void firstInstallHomeAlwaysShowsLiveAndRoutesToSetup() {
        try(var controller=Robolectric.buildActivity(com.fongmi.android.tv.ui.activity.HomeActivity.class).setup()) {
            var activity=controller.get();
            var navigation=(com.google.android.material.bottomnavigation.BottomNavigationView)activity.findViewById(R.id.navigation);
            assertTrue(navigation.getMenu().findItem(R.id.live).isVisible());
            assertTrue(navigation.getMenu().findItem(R.id.vod).isVisible());
            assertTrue(navigation.getMenu().findItem(R.id.setting).isVisible());
            assertEquals(android.view.View.VISIBLE,activity.findViewById(R.id.sourceEmpty).getVisibility());
            navigation.setSelectedItemId(R.id.live);
            var intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(com.fongmi.android.tv.ui.activity.SourceCatalogActivity.class.getName(),intent.getComponent().getClassName());
            assertEquals(1,intent.getIntExtra("source_type",-1));
        }
    }
    @Test public void firstInstallVodTapOpensEditableSourceDialog() {
        try(var controller=Robolectric.buildActivity(Host.class).setup()) {
            Host activity=controller.get();
            assertTrue("source row must have click handler",activity.findViewById(R.id.vod).performClick());
            activity.getSupportFragmentManager().findFragmentByTag("settings").getChildFragmentManager().executePendingTransactions();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNotNull("tapping source must open dialog",ShadowDialog.getLatestDialog());
            assertNotNull(ShadowDialog.getLatestDialog().findViewById(R.id.url));
            assertTrue(ShadowDialog.getLatestDialog().findViewById(R.id.url).isEnabled());
        }
    }
}
