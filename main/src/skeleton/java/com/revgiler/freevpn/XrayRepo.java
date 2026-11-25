package com.revgiler.freevpn;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Free VLESS/Trojan keys from the public collection github.com/igareck/vpn-configs-for-russia
 * (lists picked for Russian mobile networks). Our GitHub Action mirrors them to the
 * "servers" branch; several mirrors are tried because some of them are blocked in places.
 * The user's own keys ("my server") are kept separately in preferences.
 */
public class XrayRepo {
    private static final String MIRROR = "https://raw.githubusercontent.com/revgiler-arch/FreeVPN/servers/vless.txt";
    private static final String IGARECK_GH = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/";
    private static final String IGARECK_BB = "https://bitbucket.org/igareck/vpn-configs-for-russia/raw/main/";
    private static final String WHITE = "Vless-Reality-White-Lists-Rus-Mobile.txt";
    private static final String BLACK = "BLACK_VLESS_RUS_mobile.txt";

    /** Yandex Translate opens any page through its own servers; it stays reachable under Russian "white lists". */
    private static String viaYandex(String url) {
        return "https://translate.yandex.ru/translate?url=" + url + "&lang=de-de";
    }

    /** Groups of alternative URLs for the same content; the first URL that works in a group wins. */
    private static final String[][] SOURCES = {
            // our mirror (updated every 30 min): white + black lists in one file
            {MIRROR,
                    "https://cdn.jsdelivr.net/gh/revgiler-arch/FreeVPN@servers/vless.txt",
                    viaYandex(MIRROR)},
            // the original collection – white list keys (Russian IPs, for mobile "white lists")
            {IGARECK_GH + WHITE, viaYandex(IGARECK_BB + WHITE), "https://raw.githack.com/igareck/vpn-configs-for-russia/main/" + WHITE},
            // black list keys
            {IGARECK_GH + BLACK, viaYandex(IGARECK_BB + BLACK), "https://raw.githack.com/igareck/vpn-configs-for-russia/main/" + BLACK},
    };
    private static final String BUILTIN_ASSET = "vless_builtin.txt";
    private static final String CACHE = "vless_cache.txt";
    private static final String PREFS = "freevpn_keys";
    private static final String KEY_USER = "user_keys";
    private static final long SOURCE_DEADLINE_MS = 20_000;
    private static final java.util.regex.Pattern LINK =
            java.util.regex.Pattern.compile("(?:vless|trojan)://[^\\s\"'<>]+");

    public static class Result {
        public final List<XrayLink> links;
        public final boolean fromCache;

        Result(List<XrayLink> links, boolean fromCache) {
            this.links = links;
            this.fromCache = fromCache;
        }
    }

    public static Result load(Context ctx, VpnGateApi.Progress progress) throws IOException {
        StringBuilder all = new StringBuilder();
        IOException last = null;
        // Mirror first; if it works we skip the originals.
        for (int group = 0; group < SOURCES.length; group++) {
            if (group > 0 && all.length() > 0 && group == 1) break;
            if (progress != null) progress.onSource(group + 1, SOURCES.length);
            for (String url : SOURCES[group]) {
                try {
                    all.append(download(url)).append('\n');
                    break;
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        List<XrayLink> free = parseAll(all.toString(), false);
        boolean fromCache = false;
        if (free.isEmpty()) {
            String cached = readCache(ctx);
            if (cached != null) free = parseAll(cached, false);
            if (free.isEmpty()) {
                // List packed into the APK at build time – last resort when nothing is reachable.
                try (InputStream in = ctx.getAssets().open(BUILTIN_ASSET)) {
                    free = parseAll(readAll(in), false);
                } catch (IOException ignored) {
                }
            }
            fromCache = !free.isEmpty();
        } else {
            writeCache(ctx, all.toString());
        }
        List<XrayLink> result = new ArrayList<>(userKeys(ctx));
        result.addAll(free);
        if (result.isEmpty()) throw last != null ? last : new IOException("Ключи не найдены");
        return new Result(result, fromCache);
    }

    public static List<XrayLink> parseAll(String text, boolean user) {
        List<XrayLink> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        java.util.regex.Matcher m = LINK.matcher(text.replace("&amp;", "&"));
        while (m.find()) {
            XrayLink l = XrayLink.parse(m.group());
            if (l == null) continue;
            String key = l.address + ":" + l.port + "|" + l.transport + "|" + l.security;
            if (!seen.add(key)) continue;
            l.userKey = user;
            out.add(l);
        }
        return out;
    }

    // ------------------------------------------------------------------ user keys

    public static List<XrayLink> userKeys(Context ctx) {
        Set<String> set = prefs(ctx).getStringSet(KEY_USER, new LinkedHashSet<>());
        StringBuilder sb = new StringBuilder();
        for (String s : set) sb.append(s).append('\n');
        List<XrayLink> list = parseAll(sb.toString(), true);
        for (XrayLink l : list) l.name = "⭐ " + l.name;
        return list;
    }

    /** Returns how many new keys were added. */
    public static int addUserKeys(Context ctx, String pasted) {
        Set<String> set = new LinkedHashSet<>(prefs(ctx).getStringSet(KEY_USER, new LinkedHashSet<>()));
        int added = 0;
        for (String line : pasted.split("\\s+")) {
            if (XrayLink.parse(line) != null && set.add(line.trim())) added++;
        }
        prefs(ctx).edit().putStringSet(KEY_USER, set).apply();
        return added;
    }

    public static void removeUserKey(Context ctx, String link) {
        Set<String> set = new LinkedHashSet<>(prefs(ctx).getStringSet(KEY_USER, new LinkedHashSet<>()));
        set.remove(link);
        prefs(ctx).edit().putStringSet(KEY_USER, set).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ io

    private static String download(String url) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) FreeVPN");
        c.setInstanceFollowRedirects(true);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(SOURCE_DEADLINE_MS);
                c.disconnect();
            } catch (InterruptedException ignored) {
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            String body = readAll(c.getInputStream());
            if (!LINK.matcher(body).find()) throw new IOException("В ответе нет ключей");
            return body;
        } catch (RuntimeException e) {
            throw new IOException("Соединение прервано", e);
        } finally {
            watchdog.interrupt();
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static void writeCache(Context ctx, String text) {
        try (OutputStream o = new FileOutputStream(new File(ctx.getCacheDir(), CACHE))) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static String readCache(Context ctx) {
        File f = new File(ctx.getCacheDir(), CACHE);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }
}
