package com.fongmi.android.tv.ui.activity;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.source.*;
import com.fongmi.android.tv.ui.source.SourcePage;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Task;
import java.util.concurrent.Future;

/** Same update workflow on phone and TV, with explicit NAS setup and installation confirmation. */
public class UpdateActivity extends AppCompatActivity {
    private TextView status;
    private Button check,download,install;
    private UpdateRepository operation;
    private Future<?> task;
    private boolean busy;
    private final Runnable progressTick = new Runnable() { public void run() {
        if(busy && operation!=null && !isDestroyed()) {status.setText(operation.progress());App.post(this,1000);}
    }};
    public static void start(Activity activity) {activity.startActivity(new Intent(activity,UpdateActivity.class));}
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page=SourcePage.page(this,"软件更新");
        SourcePage.text(page,"当前版本："+BuildConfig.VERSION_NAME+" · "+(BuildConfig.FLAVOR.equals("leanback")?"电视版":"手机版"),16);
        SourcePage.text(page,"家中通过极空间下载更新；GitHub 可作为备用。安装需要系统确认，不会在看视频时突然安装。",15);
        SourcePage.action(page,"设置更新地址 · 极空间 / GitHub",this::configure);
        SourcePage.action(page,"家庭线路 · 设置或更换",this::configureRoute);
        SwitchCompat automatic=new SwitchCompat(this);automatic.setText("后台检查并下载（Wi-Fi / 非计费网络）");
        automatic.setTextColor(0xffe6edf3);automatic.setMinHeight(SourcePage.dp(this,64));automatic.setChecked(UpdateRepository.automatic());
        automatic.setOnCheckedChangeListener((view,enabled)->{UpdateRepository.prefs().edit().putBoolean("automatic",enabled).apply();UpdateJob.schedule(this);});page.addView(automatic);
        status=SourcePage.text(page,"",16);status.setTextIsSelectable(true);
        check=SourcePage.action(page,"检查更新",()->run(false));
        download=SourcePage.action(page,"下载并准备安装",()->run(true));
        install=SourcePage.action(page,"安装已下载的新版",this::install);
        SourcePage.action(page,"返回",this::finish);refresh();
    }
    @Override protected void onResume() {super.onResume();if(status!=null&&!busy)refresh();}
    private void refresh() {
        status.setText(UpdateRepository.status()+"\n最近下载线路："+UpdateRepository.prefs().getString("route","尚未使用"));
        check.setEnabled(!busy);download.setEnabled(!busy);install.setEnabled(!busy&&UpdateRepository.hasReadyFile());
    }
    private void configureRoute() {
        if(busy)return;
        EditText input=new EditText(this);input.setInputType(129);input.setHint("粘贴自己的 VLESS 链接");
        if(Build.VERSION.SDK_INT>=26) input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(MaintenanceTransport.configured()?"家庭线路已配置":"家庭线路未配置")
            .setMessage("仅用于指定影视源配置维护及本应用 GitHub 更新。播放、搜索和其他下载仍直连。配置只保存在本机。")
            .setView(input).setPositiveButton("保存",null).setNeutralButton("清除",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->{
            dialog.getButton(-1).setOnClickListener(v->{try {if(input.getText().toString().trim().isEmpty()){input.setError("请输入线路");return;}
                MaintenanceTransport.configure(input.getText().toString());input.setText("");dialog.dismiss();status.setText("家庭线路已保存");
            }catch(Exception|LinkageError e){input.setError("配置格式不支持或本机无法加载线路模块");}});
            dialog.getButton(-3).setOnClickListener(v->{try{MaintenanceTransport.configure("");dialog.dismiss();status.setText("家庭线路已清除，将使用直连");}catch(Exception e){status.setText("清除未成功，请重试");}});
        });dialog.show();
    }
    private void configure() {
        if(busy)return;
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);int padding=SourcePage.dp(this,20);form.setPadding(padding,0,padding,0);
        EditText primary=new EditText(this),backup=new EditText(this);
        primary.setSingleLine();backup.setSingleLine();primary.setInputType(17);backup.setInputType(17);
        primary.setHint("主要地址：manifest.json");primary.setText(UpdateRepository.address());
        backup.setHint("备用 HTTPS 清单地址（可留空）");backup.setText(UpdateRepository.prefs().getString("backup",UpdateRepository.NAS));
        form.addView(primary);form.addView(backup);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("更新地址").setMessage("极空间需要先部署下载服务。填写直接下载版本清单的地址，不是网页登录地址。")
                .setView(form).setPositiveButton("保存",null).setNeutralButton("使用家中极空间",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->{
            dialog.getButton(-3).setOnClickListener(v->primary.setText(UpdateRepository.NAS));
            dialog.getButton(-1).setOnClickListener(v->{try {UpdateRepository.configure(primary.getText().toString().trim(),backup.getText().toString().trim());dialog.dismiss();status.setText("已保存地址，点击检查更新验证连接");}catch(Exception e){primary.setError(e.getMessage());}});
        });dialog.show();
    }
    private void run(boolean fetch) {
        if(busy)return;busy=true;refresh();status.setText(fetch?"正在检查并下载，完成校验后才可安装…":"正在检查更新…");
        UpdateRepository current=new UpdateRepository();operation=current;App.post(progressTick,1000);
        task=Task.submit(()->{String message;try{message=current.check(fetch);}catch(Exception e){message=UpdateRepository.status();}
            final String result=message;App.post(()->{if(isFinishing()||isDestroyed())return;busy=false;App.removeCallbacks(progressTick);refresh();status.setText(result);});});
    }
    private void install() {
        if(busy)return;operation=null;
        if(Build.VERSION.SDK_INT>=26 && !getPackageManager().canRequestPackageInstalls()) {
            try {startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:"+BuildConfig.APPLICATION_ID)));}
            catch(Exception e){status.setText("请在系统设置中允许本应用安装更新，然后返回这里重试");}return;
        }
        busy=true;refresh();status.setText("正在校验安装包…");
        task=Task.submit(()->{try {
            ReleaseArtifact artifact=UpdateRepository.ready();if(artifact==null)throw new IllegalStateException();
            java.io.File file=UpdateRepository.file(artifact);artifact.verifyBytes(file);ApkVerifier.verifyApk(file,artifact);
            App.post(()->{if(isFinishing()||isDestroyed())return;busy=false;refresh();
                try {FileUtil.openFile(file);}catch(Exception e){status.setText("系统安装器未能打开，请重试");}});
        }catch(Exception e){App.post(()->{if(isFinishing()||isDestroyed())return;busy=false;refresh();status.setText("安装包已失效，请重新下载");});}});
    }
    @Override protected void onDestroy() {App.removeCallbacks(progressTick);if(operation!=null)operation.cancel();if(task!=null)task.cancel(true);super.onDestroy();}
}
