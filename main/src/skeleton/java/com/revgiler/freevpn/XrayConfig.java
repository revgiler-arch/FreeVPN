package com.revgiler.freevpn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Builds Xray-core JSON configs around one outbound. */
public final class XrayConfig {
    public static final String TEST_URL = "https://www.gstatic.com/generate_204";
    public static final int MTU = 1500;

    private XrayConfig() {
    }

    /** Full config for the VPN: everything from the TUN goes through the proxy. */
    public static String forVpn(XrayLink link) throws JSONException {
        JSONObject root = base(link);

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
        root.put("routing", new JSONObject().put("domainStrategy", "AsIs").put("rules", rules));
        return root.toString();
    }

    /** Config used only to measure whether a key works (no inbounds). */
    public static String forTest(XrayLink link) throws JSONException {
        JSONObject root = base(link);
        root.put("inbounds", new JSONArray());
        return root.toString();
    }

    private static JSONObject base(XrayLink link) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("loglevel", "warning"));
        root.put("stats", new JSONObject());
        root.put("policy", new JSONObject()
                .put("levels", new JSONObject().put("8", new JSONObject()
                        .put("handshake", 4).put("connIdle", 300).put("uplinkOnly", 1).put("downlinkOnly", 1)))
                .put("system", new JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true)));

        JSONArray outbounds = new JSONArray();
        outbounds.put(new JSONObject(link.outbound().toString()));
        outbounds.put(new JSONObject().put("tag", "direct").put("protocol", "freedom"));
        outbounds.put(new JSONObject().put("tag", "dns-out").put("protocol", "dns"));
        outbounds.put(new JSONObject().put("tag", "block").put("protocol", "blackhole"));
        root.put("outbounds", outbounds);
        return root;
    }
}
