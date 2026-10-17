package com.fongmi.android.tv.source;

public class DeviceAddressCheck {
    public static void main(String[] args) {
        if (!DeviceAddress.origin("http://192.168.1.2:9981/?tab=2#pair").equals("http://192.168.1.2:9981"))
            throw new AssertionError("QR tab must not corrupt /device request or lose actual port");
        if (!DeviceAddress.origin("http://[fd00::2]:9982/?tab=2").equals("http://[fd00::2]:9982"))
            throw new AssertionError("IPv6 origin");
        if (!DeviceAddress.origin("https://box.local/path").equals("https://box.local"))
            throw new AssertionError("hostname origin");
        for (String invalid : new String[]{"file:///etc/passwd", "http://user:pass@box.local", "http://", "http://box:65536", "http://box:0"}) {
            try { DeviceAddress.origin(invalid); throw new AssertionError("invalid origin accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("DeviceAddress: 8 checks passed");
    }
}
