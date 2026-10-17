package com.fongmi.android.tv.source;
import android.app.job.*;
import android.content.ComponentName;
import android.content.Context;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.utils.Task;
import java.util.concurrent.Future;
public class UpdateJob extends JobService {
    private static final int ID=0x435550;
    private UpdateRepository operation;
    private Future<?> task;
    private int generation;
    public static void schedule(Context context) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(scheduler==null) return;
        if(!UpdateRepository.automatic()) {scheduler.cancel(ID);return;}
        if(scheduler.getPendingJob(ID)==null) scheduler.schedule(new JobInfo.Builder(ID,new ComponentName(context,UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPeriodic(6*60*60_000L).build());
    }
    @Override public boolean onStartJob(JobParameters params) {
        if(!UpdateRepository.automatic() || PlaybackService.isRunning()) return false;
        if(System.currentTimeMillis()-UpdateRepository.prefs().getLong("checked",0)<6*60*60_000L) return false;
        UpdateRepository current=new UpdateRepository();operation=current;int token=++generation;
        task=Task.submit(()->{try {current.check(true);} catch(Exception ignored) {} finally {
            App.post(()->{if(token==generation) jobFinished(params,false);});
        }});return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        ++generation;if(operation!=null)operation.cancel();if(task!=null)task.cancel(true);return true;
    }
}
