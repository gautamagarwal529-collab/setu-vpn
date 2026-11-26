package de.blinkt.openvpn.core;
public final class VpnStatus {
    public static native String getLastConnectedVPNProfile();
    public static native void addStateListener(StateListener listener);
    public static native void removeStateListener(StateListener listener);
    public interface StateListener {
        void updateState(String state, String log, int id, ConnectionStatus level, android.content.Intent intent);
        void setConnectedVPN(String uuid);
    }
}
