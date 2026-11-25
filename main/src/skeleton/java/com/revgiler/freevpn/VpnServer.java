package com.revgiler.freevpn;

import java.util.Locale;

/** One server from the VPN Gate public list. */
public class VpnServer {
    public String hostName;
    public String ip;
    public long score;
    public int ping;
    public long speedBps;
    public String countryLong;
    public String countryShort;
    public int sessions;
    public long uptimeMs;
    public String configBase64;

    /** Flag emoji from a two-letter country code, e.g. "JP" -> 🇯🇵 */
    public String flag() {
        if (countryShort == null || countryShort.length() != 2) return "🌐";
        String cc = countryShort.toUpperCase(Locale.ROOT);
        int first = Character.codePointAt(cc, 0) - 'A' + 0x1F1E6;
        int second = Character.codePointAt(cc, 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }

    public String speedText() {
        double mbps = speedBps / 1_000_000.0;
        return String.format(Locale.US, "%.1f Мбит/с", mbps);
    }

    public String pingText() {
        return ping > 0 ? ping + " мс" : "—";
    }

    /** Stable id for remembering the selected server. */
    public String id() {
        return ip + "|" + hostName;
    }
}
