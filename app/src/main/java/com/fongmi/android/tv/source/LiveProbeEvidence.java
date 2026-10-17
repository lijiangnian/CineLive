package com.fongmi.android.tv.source;

/** Require uninterrupted real-time progress, not READY alone or a timestamp jump. */
public final class LiveProbeEvidence {
    private long started=-1, position, previousTime, previousPosition;
    public boolean sample(long now,long current,boolean playing,boolean decoded,long buffered) {
        if(!playing || !decoded || buffered<500 || (started>=0 &&
                (now<previousTime || current<=previousPosition || current-previousPosition>now-previousTime+750))) {
            started=-1;return false;
        }
        if(started<0){started=now;position=current;}
        previousTime=now;previousPosition=current;
        return now-started>=1500 && current-position>=1500;
    }
}
