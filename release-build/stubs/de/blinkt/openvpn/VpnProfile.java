package de.blinkt.openvpn;
public class VpnProfile {
    public java.util.HashSet<String> mAllowedAppsVpn;
    public boolean mAllowedAppsVpnAreDisallowed;
    public String mName, mUsername;
    public boolean mUseDefaultRoute, mUseDefaultRoutev6, mAllowLocalLAN, mBlockUnusedAddressFamilies;
    public native int checkProfile(android.content.Context context);
    public native String getUUIDString();
}
