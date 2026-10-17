package com.fongmi.android.tv.source;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.*;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.video.PlaceholderSurface;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Channel;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Muted local decoding probe. No maintenance proxy; video requires a rendered first frame. */
public final class LiveMediaProbe {
    private static final Semaphore SLOT = new Semaphore(1,true);
    public static boolean check(Channel channel) throws InterruptedException {
        // Contention is cancellation, not evidence that a provider is unavailable.
        if(!SLOT.tryAcquire(15,TimeUnit.SECONDS))throw new InterruptedException("Probe busy");
        Handler main=new Handler(Looper.getMainLooper());
        CountDownLatch done=new CountDownLatch(1);
        AtomicBoolean good=new AtomicBoolean(), ended=new AtomicBoolean(), released=new AtomicBoolean();
        ExoPlayer[] player={null};
        PlaceholderSurface[] surface={null};
        boolean[] firstFrame={false};
        LiveProbeEvidence evidence=new LiveProbeEvidence();
        String name=channel.getName().toLowerCase(Locale.ROOT);
        boolean radio=name.contains("广播")||name.contains("电台")||name.contains("radio")||name.matches(".*\\bfm\\b.*");
        Runnable close=()->{
            ended.set(true);
            if(!released.compareAndSet(false,true))return;
            ExoPlayer oldPlayer=player[0];player[0]=null;
            PlaceholderSurface oldSurface=surface[0];surface[0]=null;
            // Decoder/surface teardown must not escape onto the UI thread.
            try {if(oldPlayer!=null)oldPlayer.release();}catch(RuntimeException | LinkageError ignored){}
            try {if(oldSurface!=null)oldSurface.release();}catch(RuntimeException | LinkageError ignored){}
            SLOT.release();done.countDown();
        };
        Runnable inspect=new Runnable(){public void run(){
            if(ended.get() || player[0]==null)return;
            try {
            Tracks tracks=player[0].getCurrentTracks();
            boolean media=tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)?firstFrame[0]:
                    radio && tracks.isTypeSelected(C.TRACK_TYPE_AUDIO);
            if(evidence.sample(SystemClock.elapsedRealtime(),player[0].getCurrentPosition(),
                    player[0].getPlaybackState()==Player.STATE_READY && player[0].isPlaying(),
                    media,player[0].getTotalBufferedDuration())) {
                good.set(true);close.run();
            } else main.postDelayed(this,200);
            }catch(RuntimeException | LinkageError error){close.run();}
        }};
        main.post(()->{
            if(ended.get()) {close.run();return;}
            try {
                DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
                        .setConnectTimeoutMs(4000).setReadTimeoutMs(4000)
                        .setDefaultRequestProperties(channel.getHeaders());
                player[0]=new ExoPlayer.Builder(App.get())
                        .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                        .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(2000,5000,1000,1000).build()).build();
                surface[0]=PlaceholderSurface.newInstance(App.get(),false);
                player[0].setVideoSurface(surface[0]);
                player[0].setVolume(0);player[0].setPlayWhenReady(true);
                player[0].addListener(new Player.Listener(){
                    @Override public void onRenderedFirstFrame(){firstFrame[0]=true;}
                    @Override public void onPlayerError(PlaybackException error){close.run();}
                });
                player[0].setMediaItem(MediaItem.fromUri(channel.getCurrent()));player[0].prepare();main.postDelayed(inspect,200);
            } catch(RuntimeException | LinkageError error) {close.run();}
        });
        try {done.await(12,TimeUnit.SECONDS);return good.get();}
        finally {ended.set(true);main.removeCallbacks(inspect);main.post(close);}
    }
    private LiveMediaProbe() {}
}
