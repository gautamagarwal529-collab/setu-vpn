package com.paperline.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import de.blinkt.openvpn.VpnProfile;

import java.util.HashSet;
import java.util.Set;

final class VpnOptions {
    private final Context context;
    private final SharedPreferences preferences;

    VpnOptions(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = context.getSharedPreferences("setu-vpn-options",Context.MODE_PRIVATE);
    }

    boolean autoReconnect() { return preferences.getBoolean("auto_reconnect",true); }

    void setAutoReconnect(boolean enabled) {
        preferences.edit().putBoolean("auto_reconnect",enabled).apply();
        // The OpenVPN engine reads this preference when the underlying network changes.
        context.getSharedPreferences(context.getPackageName() + "_preferences",Context.MODE_PRIVATE)
                .edit().putBoolean("netchangereconnect",enabled).apply();
    }

    Set<String> excluded() {
        return new HashSet<>(preferences.getStringSet("exclude",java.util.Collections.emptySet()));
    }

    void setExcluded(Set<String> packages) {
        preferences.edit().putStringSet("exclude",new HashSet<>(packages)).apply();
    }

    Set<String> installed(Set<String> packages) {
        Set<String> result = new HashSet<>();
        for (String name : packages) {
            if (context.getPackageName().equals(name)) continue;
            try {
                context.getPackageManager().getPackageInfo(name,0);
                result.add(name);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return result;
    }

    void apply(VpnProfile profile) {
        profile.mAllowedAppsVpn = new HashSet<>(installed(excluded()));
        profile.mAllowedAppsVpnAreDisallowed = true;
    }
}
