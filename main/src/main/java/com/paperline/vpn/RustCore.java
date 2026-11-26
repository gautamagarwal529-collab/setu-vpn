package com.paperline.vpn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Android entry points for the native Setu VPN core. */
final class RustCore {
    private static final int RELAY_FIELDS = 7;

    static {
        System.loadLibrary("setu_core");
    }

    private RustCore() {}

    private static native byte[] parseDirectory(byte[] data,String commit,long now) throws IOException;

    private static native int[] orderRelays(long[] rows,boolean directory,boolean rotate,
                                            int previous,long seed);

    static native String cleanProfile(String content,String expectedHost,boolean requiresLogin)
            throws IOException;

    static native String verifyProfile(byte[] data,String hash,String host) throws IOException;

    static native String sha256(byte[] data);

    static VpnDirectory.Edition readDirectory(byte[] data,String commit,long now) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(parseDirectory(data,commit,now)).order(ByteOrder.LITTLE_ENDIAN);
        VpnDirectory.Edition edition = new VpnDirectory.Edition();
        edition.sourceTime = buffer.getLong();
        edition.fromCsv = buffer.get() != 0;
        // The full commit is needed to fetch mirror profiles. Display only its short version.
        String version = readText(buffer);
        edition.version = version.length() > 8 ? version.substring(0,8) : version;
        int count = buffer.getInt();
        edition.best = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            VpnDirectory.Relay relay = new VpnDirectory.Relay();
            int a = buffer.get() & 255;
            int b = buffer.get() & 255;
            int c = buffer.get() & 255;
            int d = buffer.get() & 255;
            relay.address = (a << 24) | (b << 16) | (c << 8) | d;
            relay.ip = a + "." + b + "." + c + "." + d;
            relay.country = new String(new char[]{(char)buffer.get(),(char)buffer.get()});
            relay.host = readText(buffer);
            relay.ping = buffer.getInt();
            relay.probePort = buffer.getInt();
            relay.speed = buffer.getLong();
            relay.score = buffer.getLong();
            relay.lastSeen = buffer.getLong();
            relay.tcpChecked = buffer.getLong();
            relay.current = buffer.get() != 0;
            relay.tcp = readText(buffer);
            relay.profileHash = readText(buffer);
            relay.jsonHash = readText(buffer);
            relay.jsonLength = buffer.getInt();
            String inline = readText(buffer);
            if (edition.fromCsv) {
                relay.inline = inline;
            } else {
                relay.commit = version;
                relay.path = "pool/configs/" + relay.profileHash + ".json";
            }
            edition.add(relay);
        }
        edition.total = edition.best.size();
        return edition;
    }

    private static String readText(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.getInt()];
        buffer.get(bytes);
        return VpnDirectory.utf8(bytes);
    }

    static List<VpnDirectory.Relay> order(List<VpnDirectory.Relay> source,boolean directory,
                                          boolean rotate,String previous,long seed) {
        if (source.isEmpty()) return new ArrayList<>();
        long now = System.currentTimeMillis();
        long[] rows = new long[source.size() * RELAY_FIELDS];
        int offset = 0;
        for (VpnDirectory.Relay relay : source) {
            rows[offset++] = relay.address;
            rows[offset++] = countryCode(relay.country);
            rows[offset++] = relay.reachable(now) ? 0 : relay.current ? 1 : 2;
            rows[offset++] = relay.ping;
            rows[offset++] = relay.speed;
            rows[offset++] = relay.lastSeen;
            rows[offset++] = relay.score;
        }
        int[] indices = orderRelays(rows,directory,rotate,countryCode(previous),seed);
        List<VpnDirectory.Relay> result = new ArrayList<>(indices.length);
        for (int index : indices) result.add(source.get(index));
        return result;
    }

    private static int countryCode(String code) {
        return code.length() == 2 ? (code.charAt(0) << 8) | code.charAt(1) : 0;
    }
}
