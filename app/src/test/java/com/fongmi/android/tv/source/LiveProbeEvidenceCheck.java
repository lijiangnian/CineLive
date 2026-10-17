package com.fongmi.android.tv.source;
public class LiveProbeEvidenceCheck {
    private static void check(boolean result){if(!result)throw new AssertionError("incorrect probe evidence");}
    public static void main(String[] args) {
        LiveProbeEvidence e=new LiveProbeEvidence();
        check(!e.sample(0,0,true,true,1000));
        check(e.sample(1600,1600,true,true,1000));
        e=new LiveProbeEvidence();
        check(!e.sample(0,0,true,false,1000) && !e.sample(2000,2000,true,false,1000));
        e=new LiveProbeEvidence();e.sample(0,0,true,true,1000);
        check(!e.sample(2000,0,true,true,1000));
        e=new LiveProbeEvidence();e.sample(0,0,true,true,1000);
        check(!e.sample(200,10_000,true,true,1000));
        e=new LiveProbeEvidence();e.sample(0,0,true,true,1000);e.sample(1000,1000,false,true,0);
        check(!e.sample(1600,1600,true,true,1000));
        check(e.sample(3200,3200,true,true,1000));
        e=new LiveProbeEvidence();e.sample(1000,0,true,true,1000);
        check(!e.sample(500,1600,true,true,1000));
        System.out.println("LiveProbeEvidence: 8 checks passed");
    }
}
