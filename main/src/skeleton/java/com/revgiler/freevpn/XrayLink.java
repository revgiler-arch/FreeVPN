package com.revgiler.freevpn;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * One share link (vless:// or trojan://) turned into an Xray outbound.
 * Field names follow current Xray-core / v2rayNG conventions.
 */
public class XrayLink {
    public final String link;
    public String name;
    public String protocol;   // vless | trojan
    public String address;
    public int port;
    public String transport;  // tcp | ws | grpc | xhttp | httpupgrade
    public String security;   // tls | reality | none
    public boolean userKey;   // added by the user ("my server")

    /** Last test result: >0 ms, -1 failed, 0 not tested yet. */
    public long delayMs = 0;

    private final JSONObject outbound;

    private XrayLink(String link, JSONObject outbound) {
        this.link = link;
        this.outbound = outbound;
    }

    public JSONObject outbound() {
        return outbound;
    }

    public String id() {
        return link;
    }

    public String subtitle() {
        String sec = "none".equals(security) ? "" : " + " + security.toUpperCase(Locale.ROOT);
        return protocol.toUpperCase(Locale.ROOT) + " · " + transport + sec;
    }

    /** Returns null for anything we can't use. */
    public static XrayLink parse(String raw) {
        if (raw == null) return null;
        String link = raw.trim();
        String lower = link.toLowerCase(Locale.ROOT);
        try {
            if (lower.startsWith("vless://")) return build(link, "vless");
            if (lower.startsWith("trojan://")) return build(link, "trojan");
        } catch (Exception ignored) {
        }
        return null;
    }

    private static XrayLink build(String link, String protocol) throws JSONException {
        Uri u = Uri.parse(link);
        String user = u.getUserInfo();
        String host = u.getHost();
        int port = u.getPort();
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(host) || port <= 0) return null;
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);

        String type = q(u, "type", "tcp");
        if ("raw".equals(type)) type = "tcp";
        String security = q(u, "security", "trojan".equals(protocol) ? "tls" : "none");
        if (TextUtils.isEmpty(security)) security = "none";

        JSONObject settings = new JSONObject();
        settings.put("address", host);
        settings.put("port", port);
        if ("vless".equals(protocol)) {
            settings.put("id", user);
            settings.put("encryption", q(u, "encryption", "none"));
            String flow = q(u, "flow", "");
            if (!flow.isEmpty()) settings.put("flow", flow);
        } else {
            settings.put("password", Uri.decode(user));
        }

        JSONObject stream = new JSONObject();
        stream.put("network", type);
        String hostHeader = q(u, "host", "");
        String path = q(u, "path", "/");
        if (path.isEmpty()) path = "/";
        String sniFromTransport = hostHeader;

        switch (type) {
            case "ws": {
                JSONObject ws = new JSONObject();
                ws.put("path", path);
                ws.put("host", hostHeader);
                stream.put("wsSettings", ws);
                break;
            }
            case "httpupgrade": {
                JSONObject hu = new JSONObject();
                hu.put("path", path);
                hu.put("host", hostHeader);
                stream.put("httpupgradeSettings", hu);
                break;
            }
            case "xhttp":
            case "splithttp": {
                stream.put("network", "xhttp");
                type = "xhttp";
                JSONObject xh = new JSONObject();
                xh.put("path", path);
                xh.put("host", hostHeader);
                String mode = q(u, "mode", "");
                if (!mode.isEmpty()) xh.put("mode", mode);
                String extra = q(u, "extra", "");
                if (!extra.isEmpty()) {
                    try {
                        xh.put("extra", new JSONObject(extra));
                    } catch (JSONException ignored) {
                    }
                }
                stream.put("xhttpSettings", xh);
                break;
            }
            case "grpc": {
                JSONObject g = new JSONObject();
                g.put("serviceName", q(u, "serviceName", ""));
                g.put("multiMode", "multi".equals(q(u, "mode", "")));
                String authority = q(u, "authority", "");
                if (!authority.isEmpty()) g.put("authority", authority);
                sniFromTransport = authority;
                stream.put("grpcSettings", g);
                break;
            }
            case "tcp":
            default: {
                type = "tcp";
                stream.put("network", "tcp");
                JSONObject tcp = new JSONObject();
                JSONObject header = new JSONObject();
                if ("http".equals(q(u, "headerType", ""))) {
                    header.put("type", "http");
                    JSONObject req = new JSONObject();
                    req.put("path", new JSONArray().put(path));
                    if (!hostHeader.isEmpty()) {
                        req.put("headers", new JSONObject().put("Host", new JSONArray().put(hostHeader)));
                    }
                    header.put("request", req);
                } else {
                    header.put("type", "none");
                }
                tcp.put("header", header);
                stream.put("tcpSettings", tcp);
            }
        }

        if ("tls".equals(security) || "reality".equals(security)) {
            stream.put("security", security);
            JSONObject t = new JSONObject();
            String sni = q(u, "sni", "");
            if (sni.isEmpty()) sni = !sniFromTransport.isEmpty() ? sniFromTransport : (isIp(host) ? "" : host);
            if (!sni.isEmpty()) t.put("serverName", sni);
            String fp = q(u, "fp", "");
            if (fp.isEmpty() && "reality".equals(security)) fp = "chrome";
            if (!fp.isEmpty()) t.put("fingerprint", fp);
            String alpn = q(u, "alpn", "");
            if (!alpn.isEmpty()) {
                JSONArray a = new JSONArray();
                for (String s : alpn.split(",")) if (!s.trim().isEmpty()) a.put(s.trim());
                if (a.length() > 0) t.put("alpn", a);
            }
            if ("tls".equals(security)) {
                boolean insecure = "1".equals(q(u, "insecure", "")) || "true".equals(q(u, "insecure", ""))
                        || "1".equals(q(u, "allowInsecure", "")) || "true".equals(q(u, "allowInsecure", ""));
                if (insecure) t.put("allowInsecure", true);
                stream.put("tlsSettings", t);
            } else {
                String pbk = q(u, "pbk", "");
                if (pbk.isEmpty()) return null; // reality without a key can't work
                t.put("publicKey", pbk);
                String sid = q(u, "sid", "");
                if (!sid.isEmpty()) t.put("shortId", sid);
                String spx = q(u, "spx", "");
                if (!spx.isEmpty()) t.put("spiderX", spx);
                stream.put("realitySettings", t);
            }
        } else {
            security = "none";
        }

        JSONObject out = new JSONObject();
        out.put("tag", "proxy");
        out.put("protocol", protocol);
        out.put("settings", settings);
        out.put("streamSettings", stream);
        out.put("mux", new JSONObject().put("enabled", false));

        XrayLink l = new XrayLink(link, out);
        l.protocol = protocol;
        l.address = host;
        l.port = port;
        l.transport = type;
        l.security = security;
        String frag = u.getFragment();
        l.name = TextUtils.isEmpty(frag) ? host : frag.trim();
        return l;
    }

    private static String q(Uri u, String key, String def) {
        String v;
        try {
            v = u.getQueryParameter(key);
        } catch (Exception e) {
            v = null;
        }
        return v == null ? def : v.trim();
    }

    private static boolean isIp(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }
}
