package de.blinkt.openvpn.core;
public final class VPNLaunchHelper {
    public static native void startOpenVpn(de.blinkt.openvpn.VpnProfile profile, android.content.Context context, String reason, boolean replace);
}
