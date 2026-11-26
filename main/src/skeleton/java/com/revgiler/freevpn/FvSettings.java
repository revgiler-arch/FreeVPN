package com.revgiler.freevpn;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** User settings shared by the screen, the VPN services and the quick settings tile. */
public final class FvSettings {
    private static final String PREFS = "freevpn_settings";

    private FvSettings() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Russian sites (.ru/.su/.рф and big RU services) go directly, not through the VPN. */
    public static boolean ruDirect(Context c) {
        return p(c).getBoolean("ru_direct", true);
    }

    public static void setRuDirect(Context c, boolean v) {
        p(c).edit().putBoolean("ru_direct", v).apply();
    }

    /** Split the TLS handshake into small pieces so DPI can't read it in one go. */
    public static boolean antiDpi(Context c) {
        return p(c).getBoolean("anti_dpi", false);
    }

    public static void setAntiDpi(Context c, boolean v) {
        p(c).edit().putBoolean("anti_dpi", v).apply();
    }

    /** Package names of apps that always bypass the VPN. */
    public static Set<String> excludedApps(Context c) {
        return new HashSet<>(p(c).getStringSet("excluded_apps", new HashSet<>()));
    }

    public static void setExcludedApps(Context c, Set<String> apps) {
        p(c).edit().putStringSet("excluded_apps", new HashSet<>(apps)).apply();
    }
}
