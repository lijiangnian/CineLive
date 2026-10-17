package com.fongmi.android.tv.source;

import java.net.URI;
import java.net.URISyntaxException;

/** QR pages contain a tab query; control requests must address the origin, not append to that query. */
public final class DeviceAddress {
    private DeviceAddress() {}
    public static String origin(String address) {
        try {
            URI uri = new URI(address.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() == 0
                    || uri.getPort() > 65535) throw new IllegalArgumentException("设备地址格式不正确");
            return new URI(uri.getScheme().toLowerCase(java.util.Locale.ROOT), null, uri.getHost(),
                    uri.getPort(), null, null, null).toASCIIString();
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("设备地址格式不正确", e);
        }
    }
}
