package com.paperline.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;

final class ConnectionHistory {
    static final long MAX_AGE = 7 * 24 * VpnDirectory.HOUR;
    private static final int MAX_HEALTH = 64, MAX_RECENT = 20, MAX_FAVORITES = 100;
    private final SharedPreferences preferences;
    private final Set<String> favoriteIds = new HashSet<>();

    ConnectionHistory(Context context) {
        preferences = context.getSharedPreferences("setu-connections",Context.MODE_PRIVATE);
        for (Entry entry : entries("favorites")) favoriteIds.add(entry.id);
    }

    static final class Entry {
        final String id, name, country, protocol;

        Entry(String id,String name,String country,String protocol) {
            this.id = id;
            this.name = name;
            this.country = country;
            this.protocol = protocol;
        }

        static Entry relay(VpnDirectory.Relay relay,String name) {
            return new Entry("relay:" + relay.country + ":" + relay.ip,name,relay.country,"OpenVPN");
        }

        JSONObject encode() throws org.json.JSONException {
            return new JSONObject().put("id",id).put("name",name).put("country",country).put("protocol",protocol);
        }

        static Entry decode(JSONObject data) throws org.json.JSONException {
            return new Entry(data.getString("id"),data.getString("name"),data.getString("country"),data.getString("protocol"));
        }
    }

    static String network(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return "other";
        Network network = cm.getActiveNetwork();
        NetworkCapabilities active = network == null ? null : cm.getNetworkCapabilities(network);
        String activeType = transport(active);
        if (!"other".equals(activeType)) return activeType;
        if (active != null && active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            for (Network candidate : cm.getAllNetworks()) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(candidate);
                if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    String type = transport(capabilities);
                    if (!"other".equals(type)) return type;
                }
            }
        }
        return "other";
    }

    private static String transport(NetworkCapabilities network) {
        if (network != null) {
            if (network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "mobile";
            if (network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        }
        return "other";
    }

    synchronized void record(Entry entry,String network,boolean success) {
        if (entry == null) return;
        if (success) remember("recent",entry,MAX_RECENT);
        if (!entry.id.startsWith("relay:")) return;
        long now = System.currentTimeMillis();
        String key = "health-" + network;
        JSONObject stats = object(key);
        JSONObject previous = stats.optJSONObject(entry.id);
        int failures = previous == null || now - previous.optLong("last") > MAX_AGE
                ? 0 : previous.optInt("misses");
        long lastSuccess = previous == null ? 0 : previous.optLong("success");
        try {
            stats.put(entry.id,new JSONObject().put("last",now).put("misses",success ? 0 : Math.min(8,failures+1))
                    .put("success",success ? now : lastSuccess));
            List<String> keys = new ArrayList<>();
            for (Iterator<String> iter = stats.keys(); iter.hasNext();) keys.add(iter.next());
            keys.sort((a,b) -> Long.compare(lastTime(stats,b),lastTime(stats,a)));
            for (int i = MAX_HEALTH; i < keys.size(); i++) stats.remove(keys.get(i));
            preferences.edit().putString(key,stats.toString()).apply();
        } catch (org.json.JSONException ignored) {
            // A corrupt history entry must never prevent a connection.
        }
    }

    Map<String,Integer> relayScores(String network,long now) {
        JSONObject stats = object("health-" + network);
        Map<String,Integer> scores = new LinkedHashMap<>();
        for (Iterator<String> iter = stats.keys(); iter.hasNext();) {
            String id = iter.next();
            JSONObject row = stats.optJSONObject(id);
            if (row == null) continue;
            int score = score(row.optInt("misses"),row.optLong("last"),row.optLong("success"),now);
            if (score != 0) scores.put(id,score);
        }
        return scores;
    }

    private static long lastTime(JSONObject stats,String id) {
        JSONObject row = stats.optJSONObject(id);
        return row == null ? 0 : row.optLong("last");
    }

    static int score(int misses,long last,long success,long now) {
        if (misses > 0 && now >= last && now - last < Math.min(900_000L,30_000L << Math.min(5,misses-1)))
            return -1;
        return misses == 0 && success > 0 && now >= success && now - success < MAX_AGE ? 1 : 0;
    }

    synchronized boolean toggleFavorite(Entry entry) {
        List<Entry> list = entries("favorites");
        if (list.removeIf(row -> entry.id.equals(row.id))) {
            write("favorites",list,MAX_FAVORITES);
            favoriteIds.remove(entry.id);
            return false;
        }
        list.add(0,entry);
        if (list.size() > MAX_FAVORITES) favoriteIds.remove(list.remove(list.size()-1).id);
        favoriteIds.add(entry.id);
        write("favorites",list,MAX_FAVORITES);
        return true;
    }

    boolean favorite(String id) {
        return favoriteIds.contains(id);
    }

    List<Entry> favorites() { return entries("favorites"); }
    List<Entry> recent() { return entries("recent"); }

    synchronized void remove(Entry entry) {
        favoriteIds.remove(entry.id);
        for (String key : new String[]{"favorites","recent"}) {
            List<Entry> list = entries(key);
            if (list.removeIf(row -> row.id.equals(entry.id))) {
                write(key,list,"favorites".equals(key) ? MAX_FAVORITES : MAX_RECENT);
            }
        }
    }

    private void remember(String key,Entry entry,int limit) {
        List<Entry> list = entries(key);
        list.removeIf(row -> entry.id.equals(row.id));
        list.add(0,entry);
        write(key,list,limit);
    }

    private List<Entry> entries(String key) {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(preferences.getString(key,"[]"));
            for (int i = 0; i < data.length(); i++) result.add(Entry.decode(data.getJSONObject(i)));
        } catch (org.json.JSONException ignored) {}
        return result;
    }

    private JSONObject object(String key) {
        try { return new JSONObject(preferences.getString(key,"{}")); }
        catch (org.json.JSONException ignored) { return new JSONObject(); }
    }

    private void write(String key,List<Entry> entries,int limit) {
        JSONArray data = new JSONArray();
        try {
            for (int i = 0; i < Math.min(entries.size(),limit); i++) data.put(entries.get(i).encode());
            preferences.edit().putString(key,data.toString()).apply();
        } catch (org.json.JSONException ignored) {}
    }
}
