package com.revgiler.freevpn;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Builds Xray-core JSON configs around one outbound. */
public final class XrayConfig {
    public static final String TEST_URL = "https://www.gstatic.com/generate_204";
    public static final int MTU = 1500;

    /** Russian domains that go around the VPN when "Russian sites directly" is on. */
    private static final String[] RU_DOMAINS = {
            "domain:ru", "domain:su", "domain:xn--p1ai", "domain:xn--80asehdb", "domain:xn--d1acj3b",
            "domain:yandex.net", "domain:yandex.com", "domain:yastatic.net", "domain:ya.ru",
            "domain:vk.com", "domain:vk.me", "domain:vkuser.net", "domain:userapi.com", "domain:vk-cdn.net",
            "domain:mycdn.me", "domain:okcdn.ru", "domain:mail.ru", "domain:max.ru",
            "domain:wbbasket.ru", "domain:wb.ru", "domain:ozon.ru", "domain:ozone.ru",
            "domain:avito.st", "domain:2gis.com", "domain:kaspersky.com",
    };

    private XrayConfig() {
    }

    /** Full config for the VPN: everything from the TUN goes through the proxy (except bypass rules). */
    public static String forVpn(Context ctx, XrayLink link) throws JSONException {
        JSONObject root = base(ctx, link);

        JSONObject tun = new JSONObject();
        tun.put("tag", "tun");
        tun.put("protocol", "tun");
        tun.put("settings", new JSONObject().put("name", "xray0").put("MTU", MTU).put("userLevel", 8));
        tun.put("sniffing", new JSONObject().put("enabled", true)
                .put("destOverride", new JSONArray().put("http").put("tls").put("quic")));
        root.put("inbounds", new JSONArray().put(tun));

        root.put("dns", new JSONObject()
                .put("servers", new JSONArray().put("1.1.1.1").put("8.8.8.8"))
                .put("queryStrategy", "UseIPv4"));

        JSONArray rules = new JSONArray();
        rules.put(new JSONObject().put("type", "field").put("inboundTag", new JSONArray().put("tun"))
                .put("port", "53").put("outboundTag", "dns-out"));
        // local networks never go through the proxy
        rules.put(new JSONObject().put("type", "field").put("outboundTag", "direct")
                .put("ip", new JSONArray().put("10.0.0.0/8").put("172.16.0.0/12").put("192.168.0.0/16")
                        .put("127.0.0.0/8").put("169.254.0.0/16").put("fc00::/7").put("fe80::/10")));
        if (FvSettings.ruDirect(ctx)) {
            JSONArray d = new JSONArray();
            for (String s : RU_DOMAINS) d.put(s);
            rules.put(new JSONObject().put("type", "field").put("outboundTag", "direct").put("domain", d));
        }
        root.put("routing", new JSONObject().put("domainStrategy", "AsIs").put("rules", rules));
        return root.toString();
    }

    /** Config used only to measure whether a key works (no inbounds). */
    public static String forTest(Context ctx, XrayLink link) throws JSONException {
        JSONObject root = base(ctx, link);
        root.put("inbounds", new JSONArray());
        return root.toString();
    }

    private static JSONObject base(Context ctx, XrayLink link) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("loglevel", "warning"));
        root.put("stats", new JSONObject());
        root.put("policy", new JSONObject()
                .put("levels", new JSONObject().put("8", new JSONObject()
                        .put("handshake", 4).put("connIdle", 300).put("uplinkOnly", 1).put("downlinkOnly", 1)))
                .put("system", new JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true)));

        JSONObject proxy = new JSONObject(link.outbound().toString());
        if (FvSettings.antiDpi(ctx)) addFragment(proxy);

        JSONArray outbounds = new JSONArray();
        outbounds.put(proxy);
        outbounds.put(new JSONObject().put("tag", "direct").put("protocol", "freedom"));
        outbounds.put(new JSONObject().put("tag", "dns-out").put("protocol", "dns"));
        outbounds.put(new JSONObject().put("tag", "block").put("protocol", "blackhole"));
        root.put("outbounds", outbounds);
        return root;
    }

    /**
     * Anti-DPI: Xray's "fragment" mask cuts the TLS ClientHello into small TCP pieces
     * (same settings as v2rayNG). Only for TLS / Reality.
     */
    private static void addFragment(JSONObject proxy) throws JSONException {
        JSONObject stream = proxy.optJSONObject("streamSettings");
        if (stream == null) return;
        String sec = stream.optString("security", "none");
        if (!"tls".equals(sec) && !"reality".equals(sec)) return;
        JSONObject settings = new JSONObject()
                .put("packets", "reality".equals(sec) ? "1-3" : "tlshello")
                .put("length", "50-100")
                .put("delay", "10-20")
                .put("maxSplit", "10");
        JSONObject mask = new JSONObject().put("type", "fragment").put("settings", settings);
        stream.put("finalmask", new JSONObject().put("tcp", new JSONArray().put(mask)));
    }
}
