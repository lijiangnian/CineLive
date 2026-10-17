package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.source.SourceStore;
import com.fongmi.android.tv.source.SubscriptionCache;
import com.fongmi.android.tv.source.MaintenanceSettings;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;

import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.Future;

/** Shared phone/TV screen; all actions are reachable with a directional remote. */
public class SourceStatusActivity extends AppCompatActivity {
    private TextView status;
    private Button refresh;
    private Future<?> task;
    private int generation;
    private Runnable deadline;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SourceStatusActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = com.fongmi.android.tv.ui.source.SourcePage.page(this,"源维护");
        com.fongmi.android.tv.ui.source.SourcePage.text(content,
                "自动检查订阅，更新失败时保留缓存。直播后台预检，优先使用近期验证可用的线路，故障时再尝试同频道备用。",15);
        com.fongmi.android.tv.ui.source.SourcePage.action(content,"内置源 · 选择点播 / 直播", () -> SourceCatalogActivity.start(this, 0));
        com.fongmi.android.tv.ui.source.SourcePage.action(content,"设置点播源", () -> configure(0));
        com.fongmi.android.tv.ui.source.SourcePage.action(content,"设置直播源", () -> configure(1));
        SwitchCompat automatic = new SwitchCompat(this);
        automatic.setText("自动检查订阅更新");
        automatic.setTextColor(0xffe6edf3);automatic.setTextSize(16);
        automatic.setChecked(MaintenanceSettings.enabled());
        automatic.setMinHeight(com.fongmi.android.tv.ui.source.SourcePage.dp(this,64));
        int[][] states = {new int[]{android.R.attr.state_checked},new int[]{}};
        automatic.setThumbTintList(new android.content.res.ColorStateList(states,new int[]{0xff79dfd1,0xff9da9b4}));
        automatic.setTrackTintList(new android.content.res.ColorStateList(states,new int[]{0xff285b59,0xff354450}));
        automatic.setOnCheckedChangeListener((button, enabled) -> MaintenanceSettings.enabled(enabled));
        content.addView(automatic);
        com.fongmi.android.tv.ui.source.SourcePage.text(content,
                com.fongmi.android.tv.source.MaintenanceTransport.description(),15);
        com.fongmi.android.tv.ui.source.SourcePage.action(content,"点播订阅 · 管理备用地址", () -> mirrors(Config.vod(), SubscriptionCache.Kind.VOD));
        com.fongmi.android.tv.ui.source.SourcePage.action(content,"直播订阅 · 管理备用地址", () -> mirrors(Config.live(), SubscriptionCache.Kind.LIVE));
        status = com.fongmi.android.tv.ui.source.SourcePage.text(content,"",15);
        status.setLineSpacing(com.fongmi.android.tv.ui.source.SourcePage.dp(this,4),1);
        refresh = com.fongmi.android.tv.ui.source.SourcePage.action(content,getString(R.string.source_check),() -> read(true));
        com.fongmi.android.tv.ui.source.SourcePage.action(content,getString(R.string.source_back),this::finish);
        read(false);
    }

    private void mirrors(Config config, SubscriptionCache.Kind kind) {
        String primary = UrlUtil.convert(config.getUrl());
        if (!primary.startsWith("https://") && !primary.startsWith("http://")) {
            new AlertDialog.Builder(this).setMessage("请先在原来的设置页添加网络订阅。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String key = SubscriptionCache.key(primary, kind);
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setText(MaintenanceSettings.mirrors(key));
        input.setHint("每行一个 HTTP / HTTPS 订阅地址");
        int padding = (int)(20 * getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(this);
        form.setPadding(padding, 0, padding, 0);
        form.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(kind == SubscriptionCache.Kind.VOD ? "点播备用地址" : "直播备用地址")
                .setMessage("填写同一发布者的备用订阅，最多 5 个。主地址失败或内容异常时自动依次尝试；全部失败保留缓存。不会改动原配置、账号或正在播放的内容。")
                .setView(form).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                MaintenanceSettings.save(key, input.getText().toString());
                dialog.dismiss();
                read(false);
            } catch (Exception e) { input.setError(e.getMessage()); }
        }));
        dialog.show();
    }

    private void configure(int type) {
        startActivity(new Intent(this, HomeActivity.class)
                .putExtra("configure_source", type)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private void read(boolean force) {
        if (task != null && !task.isDone()) task.cancel(true);
        if(deadline!=null)App.removeCallbacks(deadline);
        int request = ++generation;
        deadline=()->{
            if(isFinishing() || isDestroyed() || request!=generation)return;
            generation++;
            if(task!=null)task.cancel(true);
            com.github.catvod.net.OkHttp.cancel("SourceStatus");
            status.setText("本次检查超时，已保留现有订阅与缓存。可稍后重试；检查超时不等于所有节目失效。");
            refresh.setEnabled(true);
        };
        App.post(deadline,45_000);
        refresh.setEnabled(false);
        status.setText(R.string.source_checking);
        task = Task.submit(() -> {
            String result;
            try {
                result = describe("点播", Config.vod(), SubscriptionCache.Kind.VOD, force)
                        + "\n\n" + describe("直播", Config.live(), SubscriptionCache.Kind.LIVE, force)
                        + "\n\n" + com.fongmi.android.tv.source.LiveHealth.summary() + "\n";
            } catch (Exception error) {
                result = "读取订阅状态失败，请重试；现有订阅保持不变。";
            }
            final String message = result;
            App.post(() -> {
                if (isFinishing() || isDestroyed() || request != generation) return;
                App.removeCallbacks(deadline);
                status.setText(message);
                refresh.setEnabled(true);
            });
        });
    }

    private String describe(String title, Config config, SubscriptionCache.Kind kind, boolean force) {
        if (android.text.TextUtils.isEmpty(config.getUrl())) return title + "：尚未配置订阅，请点击上方设置源";
        try {
            String url = UrlUtil.convert(config.getUrl());
            if (force) {
                try { SubscriptionCache.refreshForMaintenance(url, "SourceStatus", kind, true); }
                catch (Exception ignored) { /* Read persisted failure/backoff state below. */ }
            }
            SourceStore.Document document = SubscriptionCache.status(url, kind);
            String state = document.text.isEmpty()
                    ? (document.failures > 0 ? "检查失败，暂无可用缓存" : "尚未检查订阅")
                    : document.fallback ? "更新失败，保留上次可用列表" : "配置检查通过（未验证媒体播放）";
            String key = SubscriptionCache.key(url, kind);
            int index = MaintenanceSettings.used(key);
            int mirrors = MaintenanceSettings.addresses(key, url).size() - 1;
            DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
            return title + "：" + state + "\n条目数：" + document.entries
                    + "\n备用地址：" + mirrors + " 个 · 最近成功使用：" + (document.checkedAt == 0 ? "暂无" : index == 0 ? "主地址" : "备用 " + index)
                    + "\n最近成功检查：" + (document.checkedAt == 0 ? "暂无" : format.format(new Date(document.checkedAt)))
                    + "\n下次检查不早于：" + (document.nextCheckAt == 0 ? "现在可检查" : format.format(new Date(document.nextCheckAt)))
                    + "\n更新通道：" + com.fongmi.android.tv.source.MaintenanceTransport.route(url)
                    + "\n连续失败：" + document.failures + " 次\n" + (kind==SubscriptionCache.Kind.VOD
                    ? "此处只检查配置与备用订阅；片源解析、播放及同集切换由播放器处理，未进行后台逐片播放检测。"
                    : "此处为配置条目数，不是可播放频道数；本机线路检测结果见下方。");
        } catch (Exception e) {
            return title + (force ? "：检查未成功，暂时没有可用缓存；请稍后再试。" : "：还没有缓存，点击检查订阅更新。");
        }
    }

    @Override
    protected void onDestroy() {
        generation++;
        if(deadline!=null)App.removeCallbacks(deadline);
        if (task != null) task.cancel(true);
        super.onDestroy();
    }
}
