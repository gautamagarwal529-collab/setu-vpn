package com.paperline.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

final class SavedConnection {
    private static final String KEY = "last_verified";
    final ConnectionHistory.Entry entry;
    final String config;

    private SavedConnection(ConnectionHistory.Entry entry,String config) {
        this.entry = entry;
        this.config = config;
    }

    static void remember(Context context,ConnectionHistory.Entry entry,String config) {
        if (entry == null || config == null || config.isEmpty()) return;
        try {
            String data = new JSONObject().put("entry",entry.encode()).put("config",config).toString();
            preferences(context).edit().putString(KEY,data).apply();
        } catch (org.json.JSONException ignored) {}
    }

    static SavedConnection read(Context context) {
        try {
            JSONObject saved = new JSONObject(preferences(context).getString(KEY,""));
            return new SavedConnection(ConnectionHistory.Entry.decode(saved.getJSONObject("entry")),saved.getString("config"));
        } catch (org.json.JSONException ignored) {
            return null;
        }
    }

    static void clear(Context context) { preferences(context).edit().remove(KEY).apply(); }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("setu-resume",Context.MODE_PRIVATE);
    }
}
