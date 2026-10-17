package com.fongmi.android.tv.source;
import java.net.URI;
/** Public hosts require HTTPS. Cleartext is limited to literal RFC1918 LAN addresses. */
public final class UpdateAddress {
    public static void validate(String address) {
        URI uri = URI.create(address);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("请输入直接下载地址");
        if ("https".equalsIgnoreCase(uri.getScheme())) return;
        if ("http".equalsIgnoreCase(uri.getScheme()) && privateIpv4(uri.getHost())) return;
        throw new IllegalArgumentException("公网地址需要 HTTPS；家庭局域网支持 HTTP");
    }
    private static boolean privateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] n = new int[4];
        for (int i=0;i<4;i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
            n[i]=Integer.parseInt(parts[i]); if(n[i]>255) return false;
        }
        return n[0]==10 || n[0]==192 && n[1]==168 || n[0]==172 && n[1]>=16 && n[1]<=31;
    }
    private UpdateAddress() { }
}
