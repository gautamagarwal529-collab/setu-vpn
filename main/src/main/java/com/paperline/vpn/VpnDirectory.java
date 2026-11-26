package com.paperline.vpn;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class VpnDirectory {
    static final String[] PRIORITY = {"US", "JP"};
    static final String[] FEATURED = "IN NP LK TH BD BT MM CN AF MV ID AE SG PK KR CA MX BR GB FR DE KE ZA AU NZ".split(" ");
    static final long HOUR = 3_600_000L;
    static final int MAX_PROFILE = 96_000;
    private static final int MAX_POOL = 16 * 1024 * 1024;
    private static final String PROJECT = "GeorgeXie2333/vpngate-list-mirror";
    private static final String RAW = "https://raw.githubusercontent.com/" + PROJECT + "/";
    private static final String CDN = "https://cdn.jsdelivr.net/gh/" + PROJECT + "@";

    static final class Provider {
        final String name, description, url;

        Provider(String name, String description, String url) {
            this.name = name;
            this.description = description;
            this.url = url;
        }
    }

    private Map<String,List<Provider>> loadProviders() {
        Map<String,List<Provider>> result = new HashMap<>();
        String all = "AM AZ BH BD KH CN HK IN ID IL JP KZ MY PK PH SA SG KR TH TR AE VN AT BE BG CZ DK EE FI FR DE GR HU IE IT LV LT LU MD NL PL PT RO RU SK ES SE CH UA GB AR BR CA CL MX US ZA AU";
        Map<String,String> specific = new HashMap<>();
        for (String entry : new String[]{"BD:bangladesh", "CN:china", "IN:india", "TH:thailand", "AE:united-arab-emirates"}) {
            String[] pair = entry.split(":");
            specific.put(pair[0],pair[1]);
        }
        for (String country : all.split(" ")) {
            if ("PK".equals(country)) continue; // Pakistan already has two built-in setup entries.
            String slug = specific.get(country);
            String base = "https://www.vpnjantit.com/free-openvpn";
            addProvider(result,country,new Provider("VPN Jantit",
                    slug == null ? "Find your country in the free OpenVPN directory" : "Get a free account and .ovpn file",
                    slug == null ? base : base + "-" + slug));
        }
        try (InputStream input = context.getAssets().open("paperline-grokssh.tsv")) {
            for (String row : utf8(read(input,16_384)).split("\n")) {
                String[] parts = row.trim().split("\t");
                if (parts.length == 2 && parts[0].matches("[A-Z]{2}") && parts[1].matches("[a-z0-9-]+")) {
                    addProvider(result,parts[0],new Provider("GrokSSH","Get a free account and .ovpn file",
                            "https://grokssh.com/openvpn-server/" + parts[1] + "/"));
                }
            }
        } catch (IOException error) {
            android.util.Log.w("PaperlineDirectory","Could not read the provider catalog",error);
        }
        for (String country : new String[]{"US","CA","GB","DE","FR"}) {
            addProvider(result,country,new Provider("VPNBook","Get the .ovpn file and current shared login",
                    "https://www.vpnbook.com/freevpn/openvpn"));
        }
        return result;
    }

    private static void addProvider(Map<String,List<Provider>> result, String country, Provider provider) {
        result.computeIfAbsent(country,k -> new ArrayList<>()).add(provider);
    }

    static String name(String code) {
        switch (code) {
            case "EU": return "Europe";
            case "US": return "USA";
            case "KR": return "South Korea";
            case "MM": return "Myanmar";
            case "TW": return "Taiwan";
            case "XK": return "Kosovo";
            case "XX": return "Other locations";
            default: return new Locale("", code).getDisplayCountry(Locale.ENGLISH);
        }
    }

    static final class Relay {
        String country;
        String ip;
        String host;
        int ping;
        int probePort;
        long speed;
        long score;
        long lastSeen;
        long tcpChecked;
        boolean current;
        String tcp;
        String commit;
        String path;
        String jsonHash;
        int jsonLength;
        String profileHash;
        String inline;

        boolean reachable() {
            return "reachable".equals(tcp) && tcpChecked > System.currentTimeMillis() - 18 * HOUR;
        }

        String caption() {
            String metric = speed >= 1_000_000 ? String.format(Locale.US,"%.0f Mbps reported", speed / 1_000_000.0) : "Volunteer relay";
            if (reachable()) return "TCP answered · " + metric;
            if (current) return "Recently listed · " + metric;
            long hours = Math.max(1, (System.currentTimeMillis() - lastSeen) / HOUR);
            return hours < 48 ? "Listed " + hours + " h ago · " + metric
                    : "Listed " + hours / 24 + " d ago · " + metric;
        }
    }

    static final class Edition {
        final Map<String,List<Relay>> countries = new LinkedHashMap<>();
        long sourceTime;
        String version = "";
        boolean fromCsv;
        int total;
        List<Relay> best = Collections.emptyList();

        void populate(List<Relay> rows) {
            Map<String,Relay> unique = new LinkedHashMap<>();
            rows.sort(Comparator.<Relay>comparingInt(r -> r.reachable() ? 0 : r.current ? 1 : 2)
                    .thenComparing(Comparator.comparingLong((Relay r) -> r.lastSeen / (3 * HOUR)).reversed())
                    .thenComparing(Comparator.comparingLong((Relay r) -> r.score).reversed()));
            for (Relay relay : rows) {
                if (unique.putIfAbsent(relay.country + "/" + relay.ip, relay) != null) continue;
                countries.computeIfAbsent(relay.country, code -> new ArrayList<>()).add(relay);
            }
            best = new ArrayList<>(unique.values());
            total = best.size();
        }
    }

    private final Context context;
    private final AtomicFile cache;
    private final AtomicFile csvCache;
    private final Map<String,List<Provider>> providers;

    VpnDirectory(Context context) {
        this.context = context.getApplicationContext();
        cache = new AtomicFile(new File(context.getCacheDir(), "directory.json"));
        csvCache = new AtomicFile(new File(context.getCacheDir(), "directory-csv.json"));
        providers = loadProviders();
    }

    Set<String> providerCountries() { return providers.keySet(); }

    boolean needsRefresh(Edition edition) {
        long fresh = System.currentTimeMillis() - 4 * HOUR;
        return edition.sourceTime < fresh || csvCache.getBaseFile().lastModified() < fresh;
    }

    Edition starter() {
        try (InputStream input = context.getAssets().open("paperline-starter.json")) {
            return readEdition(new JSONObject(utf8(read(input,512_000))));
        } catch (Exception error) {
            android.util.Log.w("PaperlineDirectory","Could not read the starter directory",error);
            return new Edition();
        }
    }

    Edition cached() {
        return combine(cachedPool(),readCache(csvCache));
    }

    private Edition cachedPool() {
        Edition disk = readCache(cache);
        Edition bundled;
        try (InputStream input = context.getAssets().open("paperline-directory.json")) {
            bundled = readEdition(new JSONObject(utf8(read(input,MAX_POOL))));
        } catch (Exception error) {
            android.util.Log.w("PaperlineDirectory","Could not read the packaged directory",error);
            bundled = new Edition();
        }
        // Releases before 1.9 also stored the official CSV in the primary cache.
        if (disk.fromCsv) return combine(bundled,disk);
        return disk.total > 0 && disk.sourceTime >= bundled.sourceTime ? disk : bundled;
    }

    private static Edition readCache(AtomicFile file) {
        try (InputStream input = file.openRead()) {
            return readEdition(new JSONObject(utf8(read(input,MAX_POOL))));
        } catch (Exception ignored) {
            return new Edition();
        }
    }

    static Edition combine(Edition pool,Edition snapshot) {
        if (pool.total == 0) return snapshot;
        if (snapshot.total == 0) return pool;
        Edition combined = new Edition();
        combined.sourceTime = Math.max(pool.sourceTime,snapshot.sourceTime);
        combined.version = pool.version + "+" + snapshot.version;
        List<Relay> rows = new ArrayList<>(pool.best);
        rows.addAll(snapshot.best);
        combined.populate(rows);
        return combined;
    }

    Edition refresh() throws Exception {
        Edition pool = null, official = null;
        Exception failure = null;
        try {
            byte[] csv = fetch("https://www.vpngate.net/api/iphone/",4 * 1024 * 1024,4200);
            JSONObject document = new JSONObject().put("paperline_csv",utf8(csv))
                    .put("source_fetched_at",Instant.now().toString());
            Edition next = readEdition(document);
            if (next.total == 0) throw new IOException("VPN Gate returned an empty list");
            persist(csvCache,utf8(document.toString()));
            official = next;
        } catch (Exception unavailable) {
            failure = unavailable;
        }
        try {
            pool = refreshPool();
        } catch (Exception unavailable) {
            if (failure == null) failure = unavailable;
        }
        if (pool == null && official == null) throw new IOException("Relay sources unavailable",failure);
        return combine(pool == null ? cachedPool() : pool,official == null ? readCache(csvCache) : official);
    }

    private Edition refreshPool() throws Exception {
        byte[] published;
        try {
            published = fetch(RAW + "main/pool/latest.json",32_768,4200);
        } catch (IOException unavailable) {
            published = fetch(CDN + "main/pool/latest.json",32_768,4200);
        }
        JSONObject index = new JSONObject(utf8(published));
        if (index.getInt("schema_version") != 1 || !"vpngate-pool".equals(index.getString("kind")))
            throw new IOException("Unknown catalog format");
        String commit = index.getString("data_commit");
        if (!commit.matches("[a-f0-9]{40}")) throw new IOException("Invalid catalog version");
        if (timestamp(index.getString("source_fetched_at")) < System.currentTimeMillis() - 48 * HOUR)
            throw new IOException("Catalog needs an update");
        JSONObject file = index.getJSONObject("files").getJSONObject("pool/servers.json");
        byte[] manifest = verified(commit,"pool/servers.json",file.getString("sha256"),
                file.getInt("bytes"),MAX_POOL);
        JSONObject data = new JSONObject(utf8(manifest));
        if (data.getInt("schema_version") != 1) throw new IOException("Unknown server format");
        data.put("paperline_commit",commit);
        Edition edition = readEdition(data);
        if (edition.total == 0) throw new IOException("Empty server catalog");
        persist(cache,utf8(data.toString()));
        return edition;
    }

    private static void persist(AtomicFile file, byte[] data) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            output.write(data);
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
    }

    static Edition readEdition(JSONObject document) throws Exception {
        Edition edition = new Edition();
        edition.sourceTime = timestamp(document.optString("source_fetched_at"));
        long now = System.currentTimeMillis();
        if (edition.sourceTime <= now - 7 * 24 * HOUR) return edition;
        List<Relay> result = new ArrayList<>();
        if (document.has("paperline_csv")) {
            edition.fromCsv = true;
            String feed = document.getString("paperline_csv");
            edition.version = digest(utf8(feed)).substring(0,8);
            for (String line : feed.split("\\r?\\n")) {
                if (line.startsWith("#") || line.startsWith("*")) continue;
                List<String> fields = csv(line);
                if (fields.size() != 15) continue;
                Relay relay = new Relay();
                relay.host = fields.get(0);
                relay.ip = fields.get(1);
                relay.country = fields.get(6);
                relay.ping = (int) number(fields.get(3));
                relay.score = number(fields.get(2));
                relay.speed = number(fields.get(4));
                relay.inline = fields.get(14);
                relay.lastSeen = edition.sourceTime;
                relay.current = edition.sourceTime > now - 18 * HOUR;
                if (valid(relay) && !relay.inline.isEmpty()) result.add(relay);
            }
        } else {
            String commit = document.getString("paperline_commit");
            if (!commit.matches("[a-f0-9]{40}")) throw new IOException("Invalid catalog version");
            edition.version = commit.substring(0,8);
            JSONArray servers = document.getJSONArray("servers");
            for (int i = 0; i < servers.length(); i++) {
                JSONObject s = servers.optJSONObject(i);
                if (s == null) continue;
                Relay r = new Relay();
                r.ip = s.optString("ip");
                r.host = s.optString("hostname");
                r.country = s.isNull("country_code") ? "XX" : s.optString("country_code");
                r.lastSeen = timestamp(s.optString("last_seen_at"));
                r.ping = s.optInt("ping_ms");
                r.probePort = publicTcpPort(s,r.ip);
                r.speed = s.optLong("speed_bps");
                r.score = s.optLong("score");
                r.current = s.optBoolean("present_in_latest_source") && edition.sourceTime > now - 18 * HOUR;
                JSONObject tcp = s.optJSONObject("tcp_probe");
                if (tcp != null) {
                    r.tcp = tcp.optString("status");
                    r.tcpChecked = timestamp(tcp.optString("checked_at"));
                }
                if (!valid(r) || r.lastSeen < now - 7 * 24 * HOUR
                        || (!r.current && "unreachable".equals(r.tcp) && r.tcpChecked > now - 16 * HOUR)) continue;
                JSONObject config = s.optJSONObject("config");
                if (config == null) continue;
                r.commit = commit;
                r.path = config.optString("path");
                r.jsonHash = config.optString("sha256");
                r.jsonLength = config.optInt("bytes");
                r.profileHash = s.optString("openvpn_config_sha256");
                if (r.profileHash.matches("[a-f0-9]{64}") && r.path.equals("pool/configs/" + r.profileHash + ".json")) result.add(r);
            }
        }
        edition.populate(result);
        return edition;
    }

    private static int publicTcpPort(JSONObject row,String ip) {
        if (!"tcp".equals(row.optString("probe_reason"))) return 0;
        JSONArray targets = row.optJSONArray("probe_targets");
        if (targets == null) return 0;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i);
            if (target == null || !ip.equals(target.optString("ip"))) continue;
            int port = target.optInt("port");
            if (port > 0 && port <= 65535) return port;
        }
        return 0;
    }

    String profile(Relay relay) throws Exception {
        byte[] profile;
        if (relay.inline != null) {
            profile = Base64.decode(relay.inline, Base64.DEFAULT);
        } else {
            byte[] bytes = bundledProfile(relay);
            if (bytes == null) bytes = verified(relay.commit, relay.path, relay.jsonHash, relay.jsonLength, 160_000, 3200);
            JSONObject file = new JSONObject(utf8(bytes));
            if (file.getInt("schema_version") != 1 || !"vpngate-pool-config".equals(file.getString("kind")))
                throw new IOException("Unsupported relay profile");
            profile = Base64.decode(file.getString("openvpn_config_base64"), Base64.DEFAULT);
            if (profile.length != file.getInt("openvpn_config_bytes") ||
                    !digest(profile).equals(relay.profileHash)) throw new IOException("Relay profile changed");
        }
        if (profile.length > MAX_PROFILE) throw new IOException("Profile is too large");
        return ProfileRules.clean(utf8(profile), relay.ip);
    }

    private byte[] bundledProfile(Relay relay) throws Exception {
        try (InputStream input = context.getAssets().open("paperline-quick/" + relay.profileHash + ".json")) {
            byte[] bytes = read(input,160_000);
            if (bytes.length == relay.jsonLength && digest(bytes).equals(relay.jsonHash)) return bytes;
        } catch (IOException ignored) {
            // An unbundled relay is retrieved from its published snapshot.
        }
        return null;
    }

    static byte[] verified(String commit, String path, String hash, int size, int limit) throws Exception {
        return verified(commit,path,hash,size,limit,9000);
    }

    private static byte[] verified(String commit, String path, String hash, int size, int limit, int timeout) throws Exception {
        if (size < 1 || size > limit || !hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid catalog descriptor");
        for (String prefix : Arrays.asList(CDN + commit + "/", RAW + commit + "/")) {
            try {
                byte[] bytes = fetch(prefix + path, size,timeout);
                if (bytes.length == size && digest(bytes).equals(hash)) return bytes;
            } catch (IOException ignored) {
                // The alternate host serves the same pinned content.
            }
        }
        throw new IOException("Could not verify the directory download");
    }

    static String pakistanProfile(int number) throws IOException {
        if (number != 1 && number != 2) throw new IOException("Unknown server");
        String profile = utf8(fetch("https://www.vpnjantit.com/download-openvpn.php?server=pk" + number,MAX_PROFILE,4000));
        return ProfileRules.provider(profile,"pk" + number + ".vpnjantit.com");
    }

    private static byte[] fetch(String url, int limit) throws IOException {
        return fetch(url,limit,9000);
    }

    private static byte[] fetch(String url, int limit, int timeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(Math.min(6000,timeout));
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "SetuVPN/1.19");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Directory server unavailable");
            try (InputStream input = connection.getInputStream()) {
                return read(input, limit);
            }
        } finally {
            connection.disconnect();
        }
    }

    static byte[] read(InputStream stream, int limit) throws IOException {
        if (stream == null) throw new IOException("File unavailable");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = stream.read(buffer)) != -1) {
            if (result.size() + size > limit) throw new IOException("File is too large");
            result.write(buffer,0,size);
        }
        return result.toByteArray();
    }

    private static String digest(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder(64);
        for (byte value : hash) result.append(String.format(Locale.US, "%02x", value & 255));
        return result.toString();
    }

    static String utf8(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static long timestamp(String value) {
        if (value == null || value.isEmpty()) return 0;
        try { return Instant.parse(value).toEpochMilli(); }
        catch (DateTimeParseException ignored) { return 0; }
    }

    private static boolean valid(Relay r) {
        return r.country.matches("[A-Z]{2}") && ProfileRules.publicV4(r.ip);
    }

    private static long number(String s) {
        try { return Math.max(0,Long.parseLong(s)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static List<String> csv(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int p = 0; p < line.length(); p++) {
            char c = line.charAt(p);
            if (c == '"') {
                if (quoted && p+1 < line.length() && line.charAt(p+1) == '"') {
                    field.append('"');
                    p++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                result.add(field.toString());
                field.setLength(0);
            } else field.append(c);
        }
        result.add(field.toString());
        return result;
    }
}
