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
    private static final String WHITE_ALL = "WHITE-CIDR-RU-all.txt";
    private static final String MIXED = "BLACK_SS%2BAll_RUS.txt";

    /** Yandex Translate opens any page through its own servers; it stays reachable under Russian "white lists". */
    private static String viaYandex(String url) {
        String inner = url;
        if (url.contains("?") || url.contains("&")) {
            try {
                inner = java.net.URLEncoder.encode(url, "UTF-8");
            } catch (java.io.UnsupportedEncodingException ignored) {
            }
        }
        return "https://translate.yandex.ru/translate?url=" + inner + "&lang=de-de";
    }

    /** Groups of alternative URLs for the same content; the first URL that works in a group wins. */
    private static final String[][] SOURCES = {
            // our mirror (updated every 30 min): white + black lists in one file
            {MIRROR,
                    "https://cdn.jsdelivr.net/gh/revgiler-arch/FreeVPN@servers/vless.txt",
                    viaYandex(MIRROR)},
            // the original collection – white list keys (Russian IPs, for mobile "white lists")
            {IGARECK_GH + WHITE, viaYandex(IGARECK_BB + WHITE), "https://raw.githack.com/igareck/vpn-configs-for-russia/main/" + WHITE},
            // more white list keys (all CIDR-whitelisted servers)
            {IGARECK_GH + WHITE_ALL, viaYandex(IGARECK_BB + WHITE_ALL), "https://raw.githack.com/igareck/vpn-configs-for-russia/main/" + WHITE_ALL},
            // black list keys
            {IGARECK_GH + BLACK, viaYandex(IGARECK_BB + BLACK), "https://raw.githack.com/igareck/vpn-configs-for-russia/main/" + BLACK},
            // VMess / Trojan keys
            {IGARECK_GH + MIXED, viaYandex(IGARECK_BB + MIXED), "https://raw.githack.com/igareck/vpn-configs-for-russia/main/" + MIXED},
    };
    private static final String BUILTIN_ASSET = "vless_builtin.txt";
    private static final String CACHE = "vless_cache.txt";
    private static final String PREFS = "freevpn_keys";
    private static final String KEY_USER = "user_keys";
    private static final String KEY_SUBS = "subscriptions";
    private static final long SOURCE_DEADLINE_MS = 20_000;
    private static final java.util.regex.Pattern LINK =
            java.util.regex.Pattern.compile("(?:vless|trojan|vmess|ss)://[^\\s\"'<>]+");

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
        List<XrayLink> free = parseAll(all.toString(), XrayLink.Source.FREE, null);
        boolean fromCache = false;
        if (free.isEmpty()) {
            String cached = readCache(ctx);
            if (cached != null) free = parseAll(cached, XrayLink.Source.FREE, null);
            if (free.isEmpty()) {
                // List packed into the APK at build time – last resort when nothing is reachable.
                try (InputStream in = ctx.getAssets().open(BUILTIN_ASSET)) {
                    free = parseAll(readAll(in), XrayLink.Source.FREE, null);
                } catch (IOException ignored) {
                }
            }
            fromCache = !free.isEmpty();
        } else {
            writeCache(ctx, all.toString());
        }
        List<XrayLink> result = new ArrayList<>(userKeys(ctx));
        for (String sub : subscriptions(ctx)) {
            try {
                result.addAll(loadSubscription(ctx, sub));
            } catch (IOException e) {
                last = e;
            }
        }
        result.addAll(free);
        if (result.isEmpty()) throw last != null ? last : new IOException("Ключи не найдены");
        return new Result(result, fromCache);
    }

    public static List<XrayLink> parseAll(String text, XrayLink.Source source, String subscription) {
        List<XrayLink> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        java.util.regex.Matcher m = LINK.matcher(text.replace("&amp;", "&"));
        while (m.find()) {
            XrayLink l = XrayLink.parse(m.group());
            if (l == null) continue;
            String key = l.address + ":" + l.port + "|" + l.transport + "|" + l.security;
            if (!seen.add(key)) continue;
            l.source = source;
            l.subscription = subscription;
            out.add(l);
        }
        return out;
    }

    // ------------------------------------------------------------------ user keys

    public static List<XrayLink> userKeys(Context ctx) {
        Set<String> set = prefs(ctx).getStringSet(KEY_USER, new LinkedHashSet<>());
        StringBuilder sb = new StringBuilder();
        for (String s : set) sb.append(s).append('\n');
        List<XrayLink> list = parseAll(sb.toString(), XrayLink.Source.USER, null);
        for (XrayLink l : list) l.name = "⭐ " + l.name;
        return list;
    }

    /** Pasted text: keys and/or subscription links. Returns {keysAdded, subscriptionsAdded}. */
    public static int[] addUserInput(Context ctx, String pasted) {
        Set<String> keys = new LinkedHashSet<>(prefs(ctx).getStringSet(KEY_USER, new LinkedHashSet<>()));
        Set<String> subs = new LinkedHashSet<>(prefs(ctx).getStringSet(KEY_SUBS, new LinkedHashSet<>()));
        int k = 0, sCount = 0;
        for (String line : pasted.split("\\s+")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String lower = t.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                if (subs.add(t)) sCount++;
            } else if (XrayLink.parse(t) != null && keys.add(t)) {
                k++;
            }
        }
        prefs(ctx).edit().putStringSet(KEY_USER, keys).putStringSet(KEY_SUBS, subs).apply();
        return new int[]{k, sCount};
    }

    // ------------------------------------------------------------------ subscriptions

    public static List<String> subscriptions(Context ctx) {
        return new ArrayList<>(prefs(ctx).getStringSet(KEY_SUBS, new LinkedHashSet<>()));
    }

    public static void removeSubscription(Context ctx, String url) {
        Set<String> subs = new LinkedHashSet<>(prefs(ctx).getStringSet(KEY_SUBS, new LinkedHashSet<>()));
        subs.remove(url);
        prefs(ctx).edit().putStringSet(KEY_SUBS, subs).apply();
        new File(ctx.getCacheDir(), subCacheName(url)).delete();
    }

    /**
     * A subscription is a URL that returns keys: a plain list or the whole list in base64
     * (the format of v2rayNG / Happ / Hiddify subscriptions). Falls back to the cached copy.
     */
    public static List<XrayLink> loadSubscription(Context ctx, String url) throws IOException {
        String text = null;
        IOException err = null;
        for (String u : new String[]{url, viaYandex(url)}) {
            try {
                text = decodeSubscription(fetch(u));
                if (LINK.matcher(text).find()) break;
                text = null;
            } catch (IOException e) {
                err = e;
            }
        }
        File cache = new File(ctx.getCacheDir(), subCacheName(url));
        if (text != null) {
            write(cache, text);
        } else if (cache.exists()) {
            try (InputStream in = new FileInputStream(cache)) {
                text = readAll(in);
            }
        }
        if (text == null) throw err != null ? err : new IOException("Подписка пустая");
        List<XrayLink> list = parseAll(text, XrayLink.Source.SUBSCRIPTION, url);
        for (XrayLink l : list) l.name = "📦 " + l.name;
        return list;
    }

    private static String decodeSubscription(String body) {
        if (LINK.matcher(body).find()) return body;
        try {
            return XrayLink.decodeBase64(body);
        } catch (Exception e) {
            return body;
        }
    }

    private static String subCacheName(String url) {
        return "sub_" + Integer.toHexString(url.hashCode()) + ".txt";
    }

    private static void write(File f, String text) {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
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
        String body = fetch(url);
        if (!LINK.matcher(body).find()) throw new IOException("В ответе нет ключей");
        return body;
    }

    private static String fetch(String url) throws IOException {
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
            return readAll(c.getInputStream());
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
