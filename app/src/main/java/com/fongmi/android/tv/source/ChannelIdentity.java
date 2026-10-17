package com.fongmi.android.tv.source;
import java.util.Locale;
/** Conservative matching: keep region, language, +, and HD variants other than display resolution. */
public final class ChannelIdentity {
    public static String name(String value) {
        String s=value.toUpperCase(Locale.ROOT).replaceAll("\\s*\\((?:\\d{3,4}P|\\d{1,2}FPS)\\)","")
                .replaceAll("\\s*\\[NOT 24/7\\]","").replaceAll("[\\s_-]","");
        return s.replaceFirst("^(CCTV(?:[1-9]|1[0-7]))(?:综合|财经|综艺|体育|电影|国防军事|电视剧|纪录|科教|戏曲|社会与法|新闻|少儿|音乐|奥林匹克|农业农村)$","$1");
    }
    public static boolean matches(String left,String right) {return !name(left).isEmpty() && name(left).equals(name(right));}
    private ChannelIdentity() {}
}
