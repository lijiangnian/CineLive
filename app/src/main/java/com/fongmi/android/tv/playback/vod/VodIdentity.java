package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;
import java.util.Locale;

/** Conservative automatic matching. Ambiguous candidates stay available for manual selection. */
public final class VodIdentity {
    static String text(String value){return value.trim().replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
    public static boolean sameTitle(Vod expected,Vod candidate){
        if(expected==null || candidate==null || text(expected.getName()).isEmpty()
                || !text(expected.getName()).equals(text(candidate.getName())))return false;
        if(!expected.getYear().matches("[12][0-9]{3}") || !expected.getYear().equals(candidate.getYear()))return false;
        if(!expected.getDirector().isEmpty() && !candidate.getDirector().isEmpty()
                && !text(expected.getDirector()).equals(text(candidate.getDirector())))return false;
        return expected.getTypeName().isEmpty() || candidate.getTypeName().isEmpty()
                || text(expected.getTypeName()).equals(text(candidate.getTypeName()));
    }
    private static String episodeKey(String value){
        String s=text(value);
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(?:第|ep?|episode)?0*([0-9]+)(?:集|话|期)?$").matcher(s);
        return m.matches()?"episode:"+m.group(1):s;
    }
    public static boolean sameEpisode(String a,String b){return !a.isEmpty() && episodeKey(a).equals(episodeKey(b));}
    public static Episode episode(Flag flag,String name,boolean movie){
        Episode match=null;
        for(Episode e:flag.getEpisodes())if(!name.isEmpty() && episodeKey(e.getName()).equals(episodeKey(name))){
            if(match!=null)return null;match=e;
        }
        if(match!=null)return match;
        // Only a known movie may map a single “HD/正片” entry without an episode number.
        return movie && flag.getEpisodes().size()==1?flag.getEpisodes().get(0):null;
    }
    private VodIdentity(){}
}
