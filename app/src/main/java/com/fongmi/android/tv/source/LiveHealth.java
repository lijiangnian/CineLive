package com.fongmi.android.tv.source;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.parser.LiveParser;
import com.fongmi.android.tv.bean.*;
import com.fongmi.android.tv.utils.Task;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Public catalog candidates are NOT backups until this device has buffered their media. */
public final class LiveHealth {
    private static final long FRESH=30*60_000L, COOLDOWN=10*60_000L;
    private static final Map<String,Long> failed=new ConcurrentHashMap<>();
    private static volatile List<Channel> catalog=Collections.emptyList();
    private static volatile long catalogAt;
    private static volatile List<Channel> activeChannels=Collections.emptyList();
    private static String networkKey(){
        android.net.ConnectivityManager cm=App.get().getSystemService(android.net.ConnectivityManager.class);
        android.net.Network network=cm==null?null:cm.getActiveNetwork();
        return network==null?"none":Long.toString(network.getNetworkHandle());
    }
    public static void observePlaylist(Live live){
        List<Channel> next=new ArrayList<>();
        for(Group group:new ArrayList<>(live.getGroups()))for(Channel original:new ArrayList<>(group.getChannel())){
            if(group.isHidden() || group.isKeep())continue;
            key(original);
            Channel copy=Channel.create(original);copy.setUrls(new ArrayList<>(original.getUrls()));
            next.add(copy);
        }
        activeChannels=Collections.unmodifiableList(next);
        schedule();
    }
    private static Future<?> warming, updating;
    private static final java.util.concurrent.atomic.AtomicInteger foreground = new java.util.concurrent.atomic.AtomicInteger();
    private static boolean unmetered(){
        android.net.ConnectivityManager network=App.get().getSystemService(android.net.ConnectivityManager.class);
        return network!=null && !network.isActiveNetworkMetered();
    }
    private static android.content.SharedPreferences prefs(){return App.get().getSharedPreferences("live-health",0);}
    private static String key(Channel c){
        if(c.getHealthIdentity()!=null)return c.getHealthIdentity();
        String id=c.getExplicitTvgId();
        String name=ChannelIdentity.name(c.getName());
        // Only explicit IDs or unambiguous national-channel aliases may merge providers.
        String identity=!id.isEmpty()?"id:"+id.toLowerCase(Locale.ROOT):
                name.matches("CCTV(?:[1-9]|1[0-7])\\+?")?"national:"+name:
                "local:"+name+":"+new TreeSet<>(c.getUrls());
        String value="v2:"+UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        c.setHealthIdentity(value);return value;
    }
    private static boolean sameStation(Channel a,Channel b){return key(a).equals(key(b));}
    private static String address(Channel c){return networkKey()+":"+c.getCurrent()+new TreeMap<>(c.getHeaders()).toString();}
    private static boolean simple(Channel c){String u=c.getCurrent();return (u.startsWith("https://")||u.startsWith("http://")) && c.getDrm()==null && c.getParse()==0 && c.getClick().isEmpty();}
    public static void failed(Channel c){
        failed.put(address(c),System.currentTimeMillis());
        for(String prefix:new String[]{"","backup:"}) {
            try {Channel saved=App.gson().fromJson(prefs().getString(prefix+"channel:"+key(c),""),Channel.class);
                if(saved!=null && saved.getCurrent().equals(c.getCurrent()))prefs().edit().remove(prefix+"time:"+key(c)).apply();
            }catch(Exception ignored){}
        }
    }
    public static Channel known(Channel c) {return known(c,Collections.emptySet());}
    private static Channel known(Channel c,Set<String> excluded) {
        for(String prefix:new String[]{"", "backup:"}) {
            if(!networkKey().equals(prefs().getString(prefix+"network:"+key(c),"")))continue;
            long time=prefs().getLong(prefix+"time:"+key(c),0);
            if(time<=0 || System.currentTimeMillis()<time || System.currentTimeMillis()-time>=FRESH)continue;
            try {Channel found=App.gson().fromJson(prefs().getString(prefix+"channel:"+key(c),""),Channel.class);
                if(found!=null && !excluded.contains(found.getCurrent()) && !cooling(found)){found.setHealthIdentity(key(c));return found;}
            } catch(Exception ignored){}
        }
        return null;
    }
    private static boolean cooling(Channel c){long age=System.currentTimeMillis()-failed.getOrDefault(address(c),0L);return age>=0 && age<COOLDOWN;}
    private static void verified(Channel selected,Channel playable) {
        Channel previous=known(selected);
        if(previous!=null && !previous.getCurrent().equals(playable.getCurrent())) {
            // known() may have returned the backup; preserve its actual verification time.
            String old=prefs().getString("channel:"+key(selected),"");
            boolean primary=old.equals(App.gson().toJson(previous));
            long time=prefs().getLong((primary?"":"backup:")+"time:"+key(selected),0);
            prefs().edit().putString("backup:channel:"+key(selected),App.gson().toJson(previous))
                .putString("backup:network:"+key(selected),networkKey())
                .putLong("backup:time:"+key(selected),time).apply();
        }
        prefs().edit().putString("channel:"+key(selected),App.gson().toJson(playable))
                .putString("network:"+key(selected),networkKey())
                .putLong("time:"+key(selected),System.currentTimeMillis()).apply();
    }
    public static synchronized void warm(List<Channel> channels) {
        if(!MaintenanceSettings.enabled() || !unmetered() || foreground.get()>0 || com.fongmi.android.tv.service.PlaybackService.isRunning() || channels.isEmpty() || (warming!=null && !warming.isDone()))return;
        List<Channel> snapshot=new ArrayList<>();
        // Recently watched first, then rotate through the remaining channels across runs.
        String recent=prefs().getString("recent","");
        for(Channel c:channels)if(key(c).equals(recent))snapshot.add(c);
        int offset=prefs().getInt("cursor",0);
        for(int i=0;i<Math.min(10,channels.size());i++)snapshot.add(channels.get((offset+i)%channels.size()));

        warming=Task.submitLarge(()->{
            try {
                for(Channel c:snapshot) {
                    if(Thread.currentThread().isInterrupted() || !MaintenanceSettings.enabled() || !unmetered() || com.fongmi.android.tv.service.PlaybackService.isRunning())return;
                    Channel first=find(c,Collections.emptySet());
                    if(first!=null)find(c,Collections.singleton(first.getCurrent()));
                    // Interrupted work retains its position for the next run.
                    int position=channels.indexOf(c);
                    if(position>=0)prefs().edit().putInt("cursor",(position+1)%channels.size()).apply();
                }
            }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        });
        Future<?> started=warming;
        Task.scheduler().schedule(()->started.cancel(true),90,TimeUnit.SECONDS);
    }
    public static synchronized Future<?> prepare(Channel selected,Set<String> excluded,Consumer<Channel> result) {
        // User selection takes priority over prewarming.
        if(warming!=null)warming.cancel(true);
        prefs().edit().putString("recent",key(selected)).apply();
        Channel snapshot=Channel.create(selected);snapshot.setUrls(new ArrayList<>(selected.getUrls()));snapshot.setIndex(selected.getIndex());
        // Preserve per-line provider headers without sharing the mutable UI channel.
        for(String line:selected.getUrls())snapshot.addVerifiedLine(selected.transport(line));
        snapshot.setIndex(selected.getIndex());
        foreground.incrementAndGet();
        FutureTask<Void> task=new FutureTask<Void>(()->{
            Channel found=null;
            try {
                found=find(snapshot,excluded);
                Channel answer=found;App.post(()->result.accept(answer));
            }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            catch(Exception ignored){App.post(()->result.accept(null));}
        }, null) {
            @Override protected void done(){foreground.decrementAndGet();App.post(LiveHealth::schedule);}
        };
        Task.largeExecutor().execute(task);
        return task;
    }
    private static Channel find(Channel selected,Set<String> excluded) throws InterruptedException {
        Channel known=known(selected,excluded);
        if(known!=null && !excluded.contains(known.getCurrent()))return known;
        List<Channel> candidates=new ArrayList<>();
        for(String url:selected.getUrls()) {
            Channel c=selected.transport(url);c.setIndex(0);candidates.add(c);
        }
        // Warmed catalog is preferred; fetching updates is a separate maintenance operation.
        for(Channel c:activeChannels) if(sameStation(selected,c))for(String url:c.getUrls())candidates.add(c.transport(url));
        for(Channel c:catalog) if(sameStation(selected,c)) candidates.add(c);
        Set<String> seen=new HashSet<>();
        // Continue from the next line after an interrupted/deadline-limited pass.
        int offset=prefs().getInt("line-cursor:"+key(selected),0);
        for(int i=0;i<candidates.size();i++) {
            int position=Math.floorMod(offset+i,candidates.size());
            Channel c=candidates.get(position);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(excluded.contains(c.getCurrent()) || !simple(c) || cooling(c) || !seen.add(address(c)))continue;
            prefs().edit().putInt("line-cursor:"+key(selected),(position+1)%candidates.size()).apply();
            String checkedNetwork=networkKey();
            boolean playable=LiveMediaProbe.check(c);
            if(!checkedNetwork.equals(networkKey()))throw new InterruptedException("Network changed during probe");
            if(playable){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                verified(selected,c);Channel answer=Channel.create(c);answer.setIndex(c.getIndex());
                answer.setHealthIdentity(key(selected));return answer;
            }
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            failed.put(address(c),System.currentTimeMillis());
        }
        return null;
    }
    public static synchronized void schedule() {
        if(!unmetered())return;
        if(!MaintenanceSettings.enabled() || (updating!=null && !updating.isDone()))return;
        warmCatalog();
        if(System.currentTimeMillis()-catalogAt<60*60_000L)return;
        updating=Task.submitLarge(()->{
            try {
                refreshCatalog();
                warmCatalog();
            }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        });
    }
    private static void warmCatalog() {
        List<Channel> preferred=new ArrayList<>(activeChannels);
        for(Channel c:catalog)if(c.getName().toUpperCase(Locale.ROOT).startsWith("CCTV"))preferred.add(c);
        preferred.addAll(catalog);
        LinkedHashMap<String,Channel> unique=new LinkedHashMap<>();
        for(Channel c:preferred)unique.putIfAbsent(key(c),c);
        warm(new ArrayList<>(unique.values()));
    }
    /** Keep work inside JobScheduler's lifetime, with bounded background resource use. */
    public static void maintain() throws InterruptedException {
        schedule();
        Future<?> update, warm;
        synchronized(LiveHealth.class){update=updating;}
        try {
            if(update!=null)update.get(90,TimeUnit.SECONDS);
            synchronized(LiveHealth.class){warm=warming;}
            if(warm!=null)warm.get(90,TimeUnit.SECONDS);
        }catch(ExecutionException | CancellationException ignored){}
        catch(TimeoutException ignored){stopBackground();}
        catch(InterruptedException e){stopBackground();throw e;}
    }
    public static synchronized void pauseBackgroundProbes() {
        if(warming!=null)warming.cancel(true);
    }
    public static synchronized void stopBackground() {
        if(warming!=null)warming.cancel(true);
        if(updating!=null)updating.cancel(true);
    }
    public static void refreshCatalog() throws InterruptedException {
        if(System.currentTimeMillis()-catalogAt<60*60_000L)return;
        List<Channel> next=new ArrayList<>();
        try {
            for(BuiltinSources.Entry entry:BuiltinSources.read(App.get(),1)) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                try {
                    String url=entry.urls.get(0);
                    String text=SubscriptionCache.refreshForMaintenance(url,"LiveHealth",SubscriptionCache.Kind.LIVE,false).text;
                    Live live=new Live(entry.name,url);LiveParser.text(live,text);
                    for(Group group:live.getGroups())for(Channel original:group.getChannel())for(String line:original.getUrls()) {
                        key(original);Channel c=Channel.create(original);c.setUrls(new ArrayList<>(List.of(line)));c.setIndex(0);next.add(c);
                    }
                }catch(InterruptedException e){throw e;}catch(Exception ignored){}
            }
        }catch(java.io.IOException ignored){}
        if(!next.isEmpty()){catalog=Collections.unmodifiableList(next);catalogAt=System.currentTimeMillis();}
    }
    public static String summary() {
        int channels=0,backups=0;long now=System.currentTimeMillis();
        for(Map.Entry<String,?> row:prefs().getAll().entrySet())if(row.getValue() instanceof Long time && time>0 && now>=time && now-time<FRESH
                && networkKey().equals(prefs().getString(row.getKey().replace("time:","network:"),""))) {
            if(row.getKey().startsWith("time:v2:"))channels++;
            if(row.getKey().startsWith("backup:time:v2:"))backups++;
        }
        String activity=com.fongmi.android.tv.service.PlaybackService.isRunning()?"播放器运行中，后台解码预检暂停，避免争抢解码器。\n":"";
        return activity+"当前网络直播本机检测："+channels+" 个频道有近期可用线路，"+backups+" 条已验证备用\nWi-Fi / 非计费网络后台检查；结果保留 30 分钟，未通过检测的线路 10 分钟后重试；预检只支持普通 HTTP(S) 线路。";
    }
    public static boolean applies(Channel c){return simple(c);}
    private LiveHealth() {}
}
