package com.revgiler.freevpn;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Downloads and parses the public server list of the VPN Gate project
 * (University of Tsukuba, https://www.vpngate.net). The list is a CSV where the
 * last column holds a ready-to-use OpenVPN config, base64-encoded.
 */
public class VpnGateApi {
    /**
     * Sources, tried in order. The first two are copies of the VPN Gate list that our
     * GitHub Action refreshes every 30 minutes ("servers" branch): GitHub is reachable
     * on networks where vpngate.net is blocked or very slow. Then VPN Gate itself.
     */
    private static final String[] URLS = {
            "https://raw.githubusercontent.com/revgiler-arch/FreeVPN/servers/servers.csv",
            "https://cdn.jsdelivr.net/gh/revgiler-arch/FreeVPN@servers/servers.csv",
            "https://www.vpngate.net/api/iphone/",
    };
    /** Hard limit for one source, so a stalled connection can't hang the list forever. */
    private static final long SOURCE_DEADLINE_MS = 25_000;

    public interface Progress {
        void onSource(int index, int total);
    }
    private static final String CACHE_FILE = "vpngate_cache.csv";

    public static class Result {
        public final List<VpnServer> servers;
        public final boolean fromCache;

        Result(List<VpnServer> servers, boolean fromCache) {
            this.servers = servers;
            this.fromCache = fromCache;
        }
    }

    /** Network first; if that fails, the last list that worked. Runs on a background thread. */
    public static Result load(Context ctx, Progress progress) throws IOException {
        IOException lastError = null;
        for (int i = 0; i < URLS.length; i++) {
            String u = URLS[i];
            if (progress != null) progress.onSource(i + 1, URLS.length);
            try {
                String csv = download(u);
                List<VpnServer> list = parse(csv);
                if (!list.isEmpty()) {
                    saveCache(ctx, csv);
                    return new Result(list, false);
                }
            } catch (IOException e) {
                lastError = e;
            }
        }
        String cached = readCache(ctx);
        if (cached != null) {
            List<VpnServer> list = parse(cached);
            if (!list.isEmpty()) return new Result(list, true);
        }
        throw lastError != null ? lastError : new IOException("Список серверов пуст");
    }

    private static String download(String url) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "FreeVPN-Android");
        // Watchdog: some networks accept the connection and then just stall.
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

    static List<VpnServer> parse(String csv) throws IOException {
        List<VpnServer> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new StringReader(csv));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#")) continue;
            String[] f = line.split(",", -1);
            if (f.length < 15) continue;
            try {
                VpnServer s = new VpnServer();
                s.hostName = f[0];
                s.ip = f[1];
                s.score = parseLong(f[2]);
                s.ping = (int) parseLong(f[3]);
                s.speedBps = parseLong(f[4]);
                s.countryLong = f[5];
                s.countryShort = f[6];
                s.sessions = (int) parseLong(f[7]);
                s.uptimeMs = parseLong(f[8]);
                // The "Message" column may itself contain commas, so the config is always the last field.
                s.configBase64 = f[f.length - 1].trim();
                if (s.configBase64.length() < 100 || s.ip.isEmpty()) continue;
                out.add(s);
            } catch (RuntimeException ignored) {
                // skip broken rows
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.score, a.score));
        return out;
    }

    /** Decoded .ovpn text of a server. */
    public static String config(VpnServer s) {
        byte[] raw = Base64.decode(s.configBase64, Base64.DEFAULT);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static long parseLong(String v) {
        v = v.trim();
        if (v.isEmpty()) return 0;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
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

    private static void saveCache(Context ctx, String csv) {
        try (OutputStream o = new FileOutputStream(new File(ctx.getCacheDir(), CACHE_FILE))) {
            o.write(csv.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static String readCache(Context ctx) {
        File f = new File(ctx.getCacheDir(), CACHE_FILE);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }
}
