package com.paperline.vpn;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
        int address;
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

        boolean reachable(long now) {
            return "reachable".equals(tcp) && tcpChecked > now - 18 * HOUR;
        }

        String caption() {
            String metric = speed >= 1_000_000 ? String.format(Locale.US,"%.0f Mbps reported", speed / 1_000_000.0) : "Volunteer relay";
            if (reachable(System.currentTimeMillis())) return "TCP answered · " + metric;
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

        void add(Relay relay) {
            best.add(relay);
            countries.computeIfAbsent(relay.country, code -> new ArrayList<>()).add(relay);
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
            return readEdition(read(input,512_000));
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
            bundled = readEdition(read(input,MAX_POOL));
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
            return readEdition(read(input,MAX_POOL));
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
        List<Relay> ordered = RustCore.order(rows,true,false,"",0);
        combined.best = new ArrayList<>(ordered.size());
        for (Relay relay : ordered) combined.add(relay);
        combined.total = combined.best.size();
        return combined;
    }

    Edition refresh() throws Exception {
        Edition pool = null, official = null;
        Exception failure = null;
        try {
            byte[] csv = fetch("https://www.vpngate.net/api/iphone/",4 * 1024 * 1024,4200);
            JSONObject document = new JSONObject().put("paperline_csv",utf8(csv))
                    .put("source_fetched_at",Instant.now().toString());
            byte[] cacheData = utf8(document.toString());
            Edition next = readEdition(cacheData);
            if (next.total == 0) throw new IOException("VPN Gate returned an empty list");
            persist(csvCache,cacheData);
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
        Edition edition = RustCore.readDirectory(manifest,commit,System.currentTimeMillis());
        if (edition.total == 0) throw new IOException("Empty server catalog");
        persist(cache,withCommit(manifest,commit));
        return edition;
    }

    private static byte[] withCommit(byte[] json,String commit) throws IOException {
        int first = 0;
        while (first < json.length && json[first] <= ' ' && json[first] > 0) first++;
        if (first == json.length || json[first] != '{') throw new IOException("Invalid relay catalog");
        byte[] header = utf8("{\"paperline_commit\":\"" + commit + "\",");
        byte[] result = new byte[header.length + json.length - first - 1];
        System.arraycopy(header,0,result,0,header.length);
        System.arraycopy(json,first + 1,result,header.length,json.length - first - 1);
        return result;
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

    private static Edition readEdition(byte[] data) throws IOException {
        return RustCore.readDirectory(data,null,System.currentTimeMillis());
    }

    String profile(Relay relay) throws Exception {
        if (relay.inline != null) return RustCore.verifyProfile(utf8(relay.inline),null,relay.ip);
        byte[] bytes = bundledProfile(relay);
        if (bytes == null) bytes = verified(relay.commit,relay.path,relay.jsonHash,relay.jsonLength,160_000,3200);
        return RustCore.verifyProfile(bytes,relay.profileHash,relay.ip);
    }

    private byte[] bundledProfile(Relay relay) throws Exception {
        try (InputStream input = context.getAssets().open("paperline-quick/" + relay.profileHash + ".json")) {
            byte[] bytes = read(input,160_000);
            if (bytes.length == relay.jsonLength && RustCore.sha256(bytes).equals(relay.jsonHash)) return bytes;
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
                if (bytes.length == size && RustCore.sha256(bytes).equals(hash)) return bytes;
            } catch (IOException ignored) {
                // The alternate host serves the same pinned content.
            }
        }
        throw new IOException("Could not verify the directory download");
    }

    static String pakistanProfile(int number) throws IOException {
        if (number != 1 && number != 2) throw new IOException("Unknown server");
        String profile = utf8(fetch("https://www.vpnjantit.com/download-openvpn.php?server=pk" + number,MAX_PROFILE,4000));
        return RustCore.cleanProfile(profile,"pk" + number + ".vpnjantit.com",true);
    }

    private static byte[] fetch(String url, int limit) throws IOException {
        return fetch(url,limit,9000);
    }

    private static byte[] fetch(String url, int limit, int timeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(Math.min(6000,timeout));
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "SetuVPN/1.21");
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

    static String utf8(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static long timestamp(String value) {
        if (value == null || value.isEmpty()) return 0;
        try { return Instant.parse(value).toEpochMilli(); }
        catch (DateTimeParseException ignored) { return 0; }
    }
}
