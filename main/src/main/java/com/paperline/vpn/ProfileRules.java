package com.paperline.vpn;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class ProfileRules {
    private static final Set<String> ALLOWED = words(
            "auth auth-nocache auth-retry auth-user-pass block-outside-dns ca cert cipher client " +
            "comp-lzo compress connect-retry connect-retry-max connect-timeout data-ciphers data-ciphers-fallback " +
            "dev dev-type dhcp-option explicit-exit-notify float fragment inactive keepalive key key-direction " +
            "mssfix nobind ns-cert-type peer-fingerprint persist-key persist-tun ping ping-exit ping-restart " +
            "proto pull pull-filter redirect-gateway remote remote-cert-eku remote-cert-ku remote-cert-tls " +
            "remote-random reneg-sec resolv-retry setenv sndbuf rcvbuf tls-auth tls-cipher tls-client " +
            "tls-crypt tls-crypt-v2 tls-version-min tls-version-max tun-mtu verb verify-x509-name mute fast-io");
    private static final Set<String> INLINE = words("ca cert key tls-auth tls-crypt tls-crypt-v2");

    private static Set<String> words(String source) {
        return new HashSet<>(Arrays.asList(source.split(" ")));
    }

    static String provider(String content, String expectedHost) throws IOException {
        String safe = clean(content,expectedHost);
        if (!safe.startsWith("auth-user-pass\n") && !safe.contains("\nauth-user-pass\n"))
            throw new IOException("Provider profile needs VPN account authentication");
        return safe;
    }

    static String clean(String content, String expectedHost) throws IOException {
        if (content.indexOf(0) >= 0 || content.length() > VpnDirectory.MAX_PROFILE)
            throw new IOException("Invalid OpenVPN file");
        StringBuilder result = new StringBuilder();
        String block = null;
        boolean ca = false;
        boolean pinned = false;
        boolean verifiesServer = false;
        boolean remote = false;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("\uFEFF")) line = line.substring(1).trim();
            if (block != null) {
                if (line.equals("</" + block + ">")) block = null;
                else if (line.contains("<") || line.contains(">")) throw new IOException("Invalid certificate block");
                result.append(line).append('\n');
                continue;
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("<") && line.endsWith(">")) {
                block = line.substring(1, line.length()-1);
                if (!INLINE.contains(block)) throw new IOException("Unsupported inline OpenVPN data");
                if ("ca".equals(block)) ca = true;
                result.append(line).append('\n');
                continue;
            }
            String[] fields = line.split("\\s+");
            String option = fields[0].toLowerCase(Locale.US);
            if (!ALLOWED.contains(option)) throw new IOException("Unsupported OpenVPN option: " + option);
            if (INLINE.contains(option)) throw new IOException("Certificates and keys must be embedded in the .ovpn file");
            if ("dev".equals(option) && (fields.length != 2 || !"tun".equals(fields[1])))
                throw new IOException("This app supports TUN profiles");
            if ("dev-type".equals(option) && (fields.length != 2 || !"tun".equals(fields[1])))
                throw new IOException("This app supports TUN profiles");
            if ("remote".equals(option)) {
                if (fields.length < 2 || fields.length > 4 || !fields[1].matches("[A-Za-z0-9.:%_-]+")
                        || (expectedHost != null && !expectedHost.equalsIgnoreCase(fields[1])))
                    throw new IOException("Unexpected VPN server address");
                if (fields.length >= 3) {
                    try {
                        int port = Integer.parseInt(fields[2]);
                        if (port < 1 || port > 65535) throw new NumberFormatException();
                    } catch (NumberFormatException error) { throw new IOException("Invalid VPN port"); }
                }
                remote = true;
            }
            if ("setenv".equals(option) && (fields.length < 2
                    || !("opt".equals(fields[1]) || "CLIENT_CERT".equals(fields[1]))))
                throw new IOException("Unsupported environment option");
            if ("setenv".equals(option) && "opt".equals(fields[1])) continue;
            if ("pull-filter".equals(option) && expectedHost != null) continue;
            if ("redirect-gateway".equals(option)) continue;
            if ("auth-user-pass".equals(option)) line = "auth-user-pass";
            if ("peer-fingerprint".equals(option)) pinned = true;
            if ("remote-cert-tls".equals(option)) {
                if (fields.length != 2 || !"server".equals(fields[1])) throw new IOException("Server certificate check required");
                verifiesServer = true;
            }
            if ("remote-cert-eku".equals(option) || "verify-x509-name".equals(option) || "ns-cert-type".equals(option)) verifiesServer = true;
            result.append(line).append('\n');
        }
        if (block != null || !remote || (!ca && !pinned)) throw new IOException("Profile needs a server and inline CA or fingerprint");
        if (!verifiesServer && !pinned) result.append("remote-cert-tls server\n");
        result.append("redirect-gateway def1 ipv6\n");
        return result.toString();
    }

    static boolean publicV4(String address) {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("(0|[1-9][0-9]{0,2})")) return false;
            bytes[i] = Integer.parseInt(parts[i]);
            if (bytes[i] > 255) return false;
        }
        int a = bytes[0], b = bytes[1], c = bytes[2];
        return a > 0 && a < 224 && a != 10 && a != 127
                && !(a == 100 && b >= 64 && b <= 127) && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31) && !(a == 192 && b == 168)
                && !(a == 192 && b == 0 && (c == 0 || c == 2))
                && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                && !(a == 203 && b == 0 && c == 113);
    }
}
