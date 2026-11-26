package de.blinkt.openvpn.core;
public final class PasswordCache {
    public static final int AUTHPASSWORD = 3;
    public static native void setCachedPassword(String uuid, int kind, String password);
}
