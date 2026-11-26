package de.blinkt.openvpn.core;
public class ConfigParser {
    public native void parseConfig(java.io.Reader input) throws Exception;
    public native de.blinkt.openvpn.VpnProfile convertProfile() throws Exception;
}
