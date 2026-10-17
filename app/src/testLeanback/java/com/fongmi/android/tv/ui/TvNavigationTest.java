package com.fongmi.android.tv.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.LiveActivity;
import com.fongmi.android.tv.ui.activity.SettingActivity;
import com.fongmi.android.tv.ui.activity.SourceCatalogActivity;
import com.fongmi.android.tv.ui.activity.VodActivity;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.ProgressLayout;
import com.fongmi.android.tv.ui.custom.TvNavigationView;
import com.github.catvod.utils.Prefers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28, application=App.class, qualifiers="zh-rCN-w960dp-h540dp-land-xhdpi",
        shadows={TvNavigationTest.NativePluginLoader.class, TvNavigationTest.OfflineSources.class, TvNavigationTest.OfflineConfig.class})
public class TvNavigationTest {
    @org.robolectric.annotation.Implements(value=com.fongmi.android.tv.api.loader.BaseLoader.class, isInAndroidSdk=false)
    public static class NativePluginLoader {
        @org.robolectric.annotation.Implementation protected void __constructor__() {}
        @org.robolectric.annotation.Implementation protected void clear() {}
        @org.robolectric.annotation.Implementation protected void clearNow() {}
    }

    // Supply fixture responses through the real LiveData boundary, with no network or playback.
    @org.robolectric.annotation.Implements(value=com.fongmi.android.tv.model.SiteViewModel.class, isInAndroidSdk=false)
    public static class OfflineSources {
        @org.robolectric.annotation.Implementation protected void homeContent() {}
        @org.robolectric.annotation.Implementation protected void categoryContent(String key, String tid, String page, boolean filter, java.util.HashMap<String, String> extend) {}
    }

    @org.robolectric.annotation.Implements(className="com.fongmi.android.tv.api.config.BaseConfig", isInAndroidSdk=false)
    public static class OfflineConfig {
        @org.robolectric.annotation.Implementation protected void loadCandidate(Config config, com.fongmi.android.tv.impl.Callback callback) {}
    }

    // The real TV layout, navigation and theme without network, player or service startup.
    public static class Host extends BaseActivity {
        ActivityHomeBinding binding;
        int vodClicks;
        @Override protected ViewBinding getBinding() {
            return binding = ActivityHomeBinding.inflate(getLayoutInflater());
        }
        @Override protected void initView(Bundle state) {
            binding.mainNavigation.setCollapsibleHeader(binding.tvChrome, binding.recycler);
            binding.mainNavigation.bind(this, () -> vodClicks++, binding.recycler, binding.title);
            binding.mainNavigation.focusLast();
        }
    }

    @Before public void reset() {
        Prefers.put("tv_black_background_v1", false);
        Setting.putWall(1);
        Prefers.remove("size");
        LiveConfig.get().config(Config.create(1));
        VodConfig.get().init();
    }

    private static void press(android.app.Activity activity, int key) {
        boolean handled = activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        // A real ViewRoot performs directional focus search after an unhandled Activity event.
        // Direct JVM dispatch does not include that input stage (ShadowActivity also stubs getCurrentFocus).
        int direction = switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT;
            default -> 0;
        };
        if (!handled && direction != 0) {
            View focus = activity.findViewById(android.R.id.content).findFocus();
            View next = focus == null ? null : focus.focusSearch(direction);
            if (next != null) next.requestFocus(direction);
        }
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    @Test public void loadingAndEmptySourcesDoNotTrapMainNavigation() {
        try (var controller = Robolectric.buildActivity(Host.class).setup().visible()) {
            var activity = controller.get();
            for (var state : new ProgressLayout.State[]{ProgressLayout.State.PROGRESS, ProgressLayout.State.EMPTY}) {
                activity.binding.progressLayout.switchState(state);
                activity.findViewById(R.id.navVod).requestFocus();
                press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
                assertTrue(activity.findViewById(R.id.navLive).hasFocus());
                press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
                assertTrue(activity.findViewById(R.id.navSettings).hasFocus());
                press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
                assertEquals(SettingActivity.class.getName(), Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
                press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
                assertTrue("hidden content must not take focus", activity.findViewById(R.id.navSettings).hasFocus());
                press(activity, KeyEvent.KEYCODE_DPAD_LEFT);
                press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
                assertEquals(SourceCatalogActivity.class.getName(), Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
                press(activity, KeyEvent.KEYCODE_DPAD_LEFT);
                press(activity, KeyEvent.KEYCODE_ENTER);
                assertEquals(state == ProgressLayout.State.PROGRESS ? 1 : 2, activity.vodClicks);
            }
        }
    }

    @Test public void configuredLiveDestinationAndFocusRestoration() {
        try (var controller = Robolectric.buildActivity(Host.class).setup().visible()) {
            var activity = controller.get();
            LiveConfig.get().config(Config.find("https://example.test/live.m3u", 1));
            activity.findViewById(R.id.navLive).requestFocus();
            press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals(LiveActivity.class.getName(), Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            activity.binding.title.requestFocus();
            activity.binding.mainNavigation.focusLast();
            assertTrue(activity.findViewById(R.id.navLive).hasFocus());
        }
    }

    @Test public void upgradeAppliesBlackOnceAndPreservesLaterWallpaperChoice() {
        try (var controller = Robolectric.buildActivity(Host.class).setup().visible()) {
            assertEquals(Setting.BLACK_WALL, Setting.getWall());
            assertEquals(0, Setting.getWallType());
            var content = (android.view.ViewGroup) controller.get().findViewById(android.R.id.content);
            assertEquals(android.graphics.Color.BLACK,
                    ((android.graphics.drawable.ColorDrawable) content.getChildAt(0).getBackground()).getColor());
            Setting.putWallType(2); // A late subscription wallpaper update must not replace solid black.
            com.fongmi.android.tv.event.ConfigEvent.wall();
            assertEquals(android.graphics.Color.BLACK, Setting.getWallColor());
            assertNull(((android.widget.ImageView) content.getChildAt(0).findViewById(R.id.image)).getDrawable());
        }
        Setting.putWall(2);
        try (var controller = Robolectric.buildActivity(Host.class).setup()) {
            assertEquals(2, Setting.getWall());
        }
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void homeCompletionDoesNotStealFocusAndTitleDownHandlesEmptyGrid() throws Exception {
        try (var controller = Robolectric.buildActivity(HomeActivity.class).setup().visible()) {
            var activity = controller.get();
            activity.findViewById(R.id.navSettings).requestFocus();
            var complete = HomeActivity.class.getDeclaredMethod("showContent");
            complete.setAccessible(true);
            complete.invoke(activity);
            assertTrue(activity.findViewById(R.id.navSettings).hasFocus());
            render(activity, "tv-home-black.png");
            activity.findViewById(R.id.title).requestFocus();
            ((ProgressLayout) activity.findViewById(R.id.progressLayout)).showProgress();
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
            assertTrue(activity.findViewById(R.id.navSettings).hasFocus());
        }
    }

    private static void render(android.app.Activity activity, String name) throws Exception {
        layout(activity);
        View root = activity.findViewById(android.R.id.content);
        prepareSnapshot(root);
        var bitmap = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        java.io.File output = new java.io.File("build/reports/tv-preview", name);
        output.getParentFile().mkdirs();
        try (var stream = new java.io.FileOutputStream(output)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
        }
        bitmap.recycle();
    }

    private static void prepareSnapshot(View view) {
        // Render the live hierarchy into a software bitmap, without stale animation display lists.
        view.setLayerType(View.LAYER_TYPE_NONE, null);
        view.invalidate();
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) prepareSnapshot(group.getChildAt(i));
        }
    }

    private static void layout(android.app.Activity activity) {
        View root = activity.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
    }

    private static void settle(android.app.Activity activity) {
        for (int i = 0; i < 3; i++) {
            layout(activity);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150));
        }
    }

    private static Result posters() {
        String[] names = {"海岸来信", "雨夜归途", "远山之上", "星海旅人", "城南旧事", "最后一班车",
                "风起时", "无声的告白", "夏日回声", "北方来客", "穿越黎明", "月下漫游",
                "夜色温柔", "向海而行", "光影之间", "深蓝时刻", "故乡的云", "明日之后"};
        var items = new java.util.ArrayList<com.fongmi.android.tv.bean.Vod>();
        for (int i = 0; i < names.length; i++) {
            var item = new com.fongmi.android.tv.bean.Vod();
            item.setId("fixture-" + i);
            item.setName(names[i]);
            items.add(item);
        }
        Result result = Result.empty();
        result.setList(items);
        return result;
    }

    private static void deliver(androidx.lifecycle.ViewModelStoreOwner owner, Result result) {
        var model = new androidx.lifecycle.ViewModelProvider(owner).get(com.fongmi.android.tv.model.SiteViewModel.class);
        ((androidx.lifecycle.MutableLiveData<Result>) model.getResult()).setValue(result);
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void homeWallHidesEntireHeaderAndRemoteRestoresMenuWithoutLosingPosition() throws Exception {
        try (var controller = Robolectric.buildActivity(HomeActivity.class).setup().visible()) {
            var activity = controller.get();
            var complete = HomeActivity.class.getDeclaredMethod("showContent");
            complete.setAccessible(true);
            complete.invoke(activity);
            deliver(activity, posters());
            settle(activity);
            var grid = (androidx.leanback.widget.VerticalGridView) activity.findViewById(R.id.recycler);
            assertEquals("all chrome must collapse, including logo and shortcuts", View.GONE, activity.findViewById(R.id.tvChrome).getVisibility());
            assertTrue(grid.hasFocus());
            assertEquals(0, grid.getTop());
            assertEquals(1080, grid.getHeight());
            assertEquals(6, com.fongmi.android.tv.Product.getColumn());
            render(activity, "tv-wall-collapsed.png");
            press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
            settle(activity);
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
            settle(activity);
            int position = grid.getSelectedPosition();
            assertNotNull("the poster wall must retain focus while scrolling", grid.findFocus());
            assertTrue("down must reach the next poster row", position > 1);
            String selected = ((android.widget.TextView) grid.findFocus().findViewById(R.id.name)).getText().toString();
            activity.getOnBackPressedDispatcher().onBackPressed();
            settle(activity);
            assertEquals(View.VISIBLE, activity.findViewById(R.id.tvChrome).getVisibility());
            assertTrue(activity.findViewById(R.id.mainNavigation).hasFocus());
            assertEquals(position, grid.getSelectedPosition());
            render(activity, "tv-wall-menu.png");
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
            settle(activity);
            assertEquals(View.GONE, activity.findViewById(R.id.tvChrome).getVisibility());
            assertEquals(position, grid.getSelectedPosition());
            assertEquals("return from the menu must keep the selected poster", selected,
                    ((android.widget.TextView) grid.findFocus().findViewById(R.id.name)).getText().toString());
            grid.setSelectedPosition(1);
            settle(activity);
            press(activity, KeyEvent.KEYCODE_DPAD_UP);
            settle(activity);
            assertTrue(activity.findViewById(R.id.mainNavigation).hasFocus());
            press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
            press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
            assertTrue(activity.findViewById(R.id.navSettings).hasFocus());
            complete.invoke(activity);
            assertTrue(activity.findViewById(R.id.navSettings).hasFocus());
            press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals(SettingActivity.class.getName(), Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
            settle(activity);
            press(activity, KeyEvent.KEYCODE_MENU);
            assertTrue(activity.findViewById(R.id.navSettings).hasFocus());
        }
    }

    @Test public void vodWallRestoresCategoriesAndMainMenuFromHiddenState() {
        Intent intent = new Intent(App.get(), VodActivity.class).putExtra("key", "test")
                .putExtra("result", Result.objectFrom("{\"class\":[{\"type_id\":\"1\",\"type_name\":\"电影\"}]}"));
        try (var controller = Robolectric.buildActivity(VodActivity.class, intent).setup().visible()) {
            var activity = controller.get();
            activity.getSupportFragmentManager().executePendingTransactions();
            var folder = activity.getSupportFragmentManager().getFragments().get(0);
            folder.getChildFragmentManager().executePendingTransactions();
            var type = folder.getChildFragmentManager().getFragments().get(0);
            deliver(type, posters());
            settle(activity);
            var wall = (com.fongmi.android.tv.ui.custom.CustomVerticalGridView) type.requireView().findViewById(R.id.recycler);
            var categories = activity.findViewById(R.id.recycler);
            assertTrue(wall.hasFocus());
            assertEquals(View.GONE, activity.findViewById(R.id.tvChrome).getVisibility());
            press(activity, KeyEvent.KEYCODE_DPAD_UP);
            settle(activity);
            assertTrue(categories.hasFocus());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.tvChrome).getVisibility());
            press(activity, KeyEvent.KEYCODE_DPAD_UP);
            assertTrue(activity.findViewById(R.id.mainNavigation).hasFocus());
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
            settle(activity);
            assertTrue(wall.hasFocus());
            assertEquals(View.GONE, activity.findViewById(R.id.tvChrome).getVisibility());
            activity.getOnBackPressedDispatcher().onBackPressed();
            settle(activity);
            assertFalse(activity.isFinishing());
            assertTrue(activity.findViewById(R.id.mainNavigation).hasFocus());
        }
    }

    @Test public void vodCategoriesCanReachLiveAndSettingsWithRemote() {
        Intent intent = new Intent(App.get(), VodActivity.class)
                .putExtra("key", "test")
                .putExtra("result", Result.objectFrom("{\"class\":[{\"type_id\":\"1\",\"type_name\":\"电影\"},{\"type_id\":\"2\",\"type_name\":\"电视剧\"}]}"));
        try (var controller = Robolectric.buildActivity(VodActivity.class, intent).setup().visible()) {
            var activity = controller.get();
            activity.findViewById(R.id.recycler).requestFocus();
            press(activity, KeyEvent.KEYCODE_DPAD_UP);
            assertTrue(activity.findViewById(R.id.navVod).hasFocus());
            press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
            press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals(SourceCatalogActivity.class.getName(), Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
            press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals(SettingActivity.class.getName(), Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            ((androidx.viewpager.widget.ViewPager) activity.findViewById(R.id.pager)).setCurrentItem(1, false);
            assertTrue("a delayed category change must not steal navigation focus", activity.findViewById(R.id.navSettings).hasFocus());
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
            assertTrue(activity.findViewById(R.id.recycler).hasFocus());
            press(activity, KeyEvent.KEYCODE_DPAD_UP);
            assertTrue(activity.findViewById(R.id.navSettings).hasFocus());
        }
    }
}
