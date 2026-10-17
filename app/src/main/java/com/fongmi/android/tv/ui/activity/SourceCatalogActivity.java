package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.*;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.source.*;
import com.fongmi.android.tv.ui.source.SourcePage;
import com.fongmi.android.tv.utils.Task;
import java.util.*;
import java.util.concurrent.Future;

/** Built-in source selection is independent of the former 115 module. */
public class SourceCatalogActivity extends AppCompatActivity {
    private TextView status;
    private LinearLayout rows;
    private int type;
    private boolean busy;
    private Future<?> work;
    private int activationId;
    private final Runnable deadline=()->{
        activationId++;if(work!=null)work.cancel(true);setBusy(false);
        status.setText("检查订阅超时，请重试或选择其他源。原订阅保持不变。");
    };
    private final List<View> actions=new ArrayList<>();

    public static void start(Activity activity,int type) {
        activity.startActivity(new Intent(activity,SourceCatalogActivity.class).putExtra("source_type",type));
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        type=saved==null ? getIntent().getIntExtra("source_type",0) : saved.getInt("source_type",0);
        LinearLayout content=SourcePage.page(this,"内置源");
        SourcePage.text(content,"选择订阅后自动检查并载入。点播和直播分别设置，已启用的订阅由源维护定期检查更新。",15);
        LinearLayout tabs=new LinearLayout(this);content.addView(tabs);
        for(int t=0;t<2;t++) {
            final int selected=t;
            TextView tab=SourcePage.action(tabs,t==0 ? "点播源" : "直播源",()->{type=selected;showRows();});
            tab.setId(t==0 ? com.fongmi.android.tv.R.id.catalogVod : com.fongmi.android.tv.R.id.catalogLive);
            LinearLayout.LayoutParams tabParams=new LinearLayout.LayoutParams(0,-2,1);
            if(t==0)tabParams.rightMargin=SourcePage.dp(this,12);
            tab.setLayoutParams(tabParams);actions.add(tab);
        }
        status=SourcePage.text(content,"",15);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        actions.add(SourcePage.action(content,"填写自己的源地址  ›",()->{
            startActivity(new Intent(this,HomeActivity.class).putExtra("configure_source",type)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }));
        rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);content.addView(rows);
        SourcePage.action(content,"返回",this::finish);
        showRows();
    }
    private void showRows() {
        rows.removeAllViews();
        for(int i=0;i<2;i++) {
            actions.get(i).setSelected(i==type);
            ((TextView)actions.get(i)).setTextColor(i==type ? 0xff79dfd1 : 0xffffffff);
        }
        try {
            List<BuiltinSources.Entry> entries=BuiltinSources.read(this,type);
            Config current=type==0 ? VodConfig.get().getConfig() : LiveConfig.get().getConfig();
            status.setText((type==0 ? "点播" : "直播")+" · "+entries.size()+" 个内置订阅\n当前："+
                    (current.getUrl().isEmpty() ? "未选择" : current.getDesc()));
            for(BuiltinSources.Entry entry:entries) {
                boolean active=entry.urls.contains(current.getUrl());
                SourcePage.action(rows,(active?"✓ ":"")+entry.name+"\n"+entry.note,()->activate(entry));
            }
        } catch(Exception e) {status.setText("目录读取失败，请返回重试或填写自己的源地址。");}
    }
    private void setBusy(boolean value) {
        if(!value)App.removeCallbacks(deadline);
        busy=value;for(View view:actions)view.setEnabled(!value);
        for(int i=0;i<rows.getChildCount();i++)rows.getChildAt(i).setEnabled(!value);
    }
    protected void activate(BuiltinSources.Entry entry) {
        if(busy)return;
        setBusy(true);status.setText("正在检查 "+entry.name+"…");
        App.post(deadline,45_000);attempt(entry,0,++activationId);
    }
    private void attempt(BuiltinSources.Entry entry,int index,int request) {
        if(isFinishing()||isDestroyed()||request!=activationId)return;
        if(index>=entry.urls.size()) {
            setBusy(false);status.setText(entry.name+" 暂时无法载入，请选择其他源。原订阅未替换。");return;
        }
        String url=entry.urls.get(index);
        work=Task.submit(()->{
            try {
                SubscriptionCache.Kind kind=entry.type==0 ? SubscriptionCache.Kind.VOD : SubscriptionCache.Kind.LIVE;
                SubscriptionCache.refreshForMaintenance(url,"BuiltinSource",kind,false);
                String key=SubscriptionCache.key(url,kind);
                if(MaintenanceSettings.mirrors(key).isEmpty() && entry.urls.size()>1) {
                    List<String> mirrors=new ArrayList<>(entry.urls);mirrors.remove(url);
                    MaintenanceSettings.save(key,String.join("\n",mirrors));
                }
                App.post(()->{
                    if(isFinishing()||isDestroyed()||request!=activationId)return;
                    App.removeCallbacks(deadline); // BaseConfig owns the bounded config-load phase.
                    // Persist only after preflight; remove a new unsuccessful history entry on load error.
                    Config previous=com.fongmi.android.tv.db.AppDatabase.get().getConfigDao().find(url,entry.type);
                    Config candidate=previous==null ? Config.create(entry.type,url,entry.name) : previous;
                    Callback callback=new Callback() {
                        @Override public void success() {
                            if(isFinishing()||isDestroyed()||request!=activationId)return;
                            setBusy(false);status.setText(entry.name+" 已启用");
                            if(entry.type==1)LiveActivity.start(SourceCatalogActivity.this);
                            else startActivity(new Intent(SourceCatalogActivity.this,HomeActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
                            finish();
                        }
                        @Override public void error(String message) {
                            if(previous==null && candidate.getTime()==0)Config.delete(url,entry.type);
                            attempt(entry,index+1,request);
                        }
                    };
                    if(entry.type==0)VodConfig.load(candidate,callback);else LiveConfig.load(candidate,callback);
                });
            } catch(Exception e) {App.post(()->attempt(entry,index+1,request));}
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) {out.putInt("source_type",type);super.onSaveInstanceState(out);}
    @Override protected void onDestroy() {
        activationId++;App.removeCallbacks(deadline);
        if(work!=null)work.cancel(true);
        com.github.catvod.net.OkHttp.cancel("BuiltinSource");
        super.onDestroy();
    }
}
