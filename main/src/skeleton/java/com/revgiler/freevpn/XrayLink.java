package com.revgiler.freevpn;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One share link turned into an Xray outbound.
 * Supported: vless://, trojan://, vmess:// (base64 JSON), ss:// (SIP002 and legacy).
 * Field names follow current Xray-core / v2rayNG conventions.
 */
public class XrayLink {
    public enum Source {USER, SUBSCRIPTION, FREE}

    public final String link;
    public String name;
    public String protocol;   // vless | trojan | vmess | shadowsocks
    public String address;
    public int port;
    public String transport;  // tcp | ws | grpc | xhttp | httpupgrade
    public String security;   // tls | reality | none
    public Source source = Source.FREE;
    public String subscription; // subscription URL this key came from

    /** Last test result: >0 ms, -1 failed, 0 not tested yet. */
    public long delayMs = 0;

    private JSONObject outbound;

    private XrayLink(String link) {
        this.link = link;
    }

    public JSONObject outbound() {
        return outbound;
    }

    public String id() {
        return link;
    }

    public boolean userKey() {
        return source == Source.USER;
    }

    public String subtitle() {
        String sec = "none".equals(security) ? "" : " + " + security.toUpperCase(Locale.ROOT);
        String proto = "shadowsocks".equals(protocol) ? "SS" : protocol.toUpperCase(Locale.ROOT);
        return proto + " · " + transport + sec;
    }

    /** Returns null for anything we can't use. */
    public static XrayLink parse(String raw) {
        if (raw == null) return null;
        String link = raw.trim();
        String lower = link.toLowerCase(Locale.ROOT);
        try {
            if (lower.startsWith("vless://")) return fromUri(link, "vless");
            if (lower.startsWith("trojan://")) return fromUri(link, "trojan");
            if (lower.startsWith("vmess://")) return fromVmess(link);
            if (lower.startsWith("ss://")) return fromShadowsocks(link);
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ vless / trojan

    private static XrayLink fromUri(String link, String protocol) throws JSONException {
        Uri u = Uri.parse(link);
        String user = u.getUserInfo();
        String host = stripBrackets(u.getHost());
        int port = u.getPort();
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(host) || port <= 0) return null;

        Map<String, String> p = queryMap(u);
        if (!p.containsKey("security")) p.put("security", "trojan".equals(protocol) ? "tls" : "none");

        JSONObject settings = new JSONObject();
        settings.put("address", host);
        settings.put("port", port);
        if ("vless".equals(protocol)) {
            settings.put("id", user);
            settings.put("encryption", get(p, "encryption", "none"));
            String flow = get(p, "flow", "");
            if (!flow.isEmpty()) settings.put("flow", flow);
        } else {
            settings.put("password", Uri.decode(user));
        }
        return finish(link, protocol, host, port, settings, p, u.getFragment());
    }

    // ------------------------------------------------------------------ vmess

    private static XrayLink fromVmess(String link) throws JSONException {
        String body = link.substring("vmess://".length());
        int hash = body.indexOf('#');
        if (hash >= 0) body = body.substring(0, hash);
        JSONObject j = new JSONObject(decodeBase64(body));
        String host = j.optString("add");
        int port = parseInt(j.optString("port"));
        String id = j.optString("id");
        if (host.isEmpty() || port <= 0 || id.isEmpty()) return null;

        String net = j.optString("net", "tcp");
        if (net.isEmpty()) net = "tcp";
        Map<String, String> p = new HashMap<>();
        p.put("type", net);
        p.put("host", j.optString("host"));
        p.put("path", j.optString("path"));
        p.put("security", "tls".equalsIgnoreCase(j.optString("tls")) ? "tls" : "none");
        p.put("sni", j.optString("sni"));
        p.put("fp", j.optString("fp"));
        p.put("alpn", j.optString("alpn"));
        p.put("insecure", j.optString("insecure"));
        if ("grpc".equals(net)) {
            p.put("serviceName", j.optString("path"));
            p.put("mode", j.optString("type"));
            p.put("authority", j.optString("host"));
        } else {
            p.put("headerType", j.optString("type"));
        }

        JSONObject settings = new JSONObject();
        settings.put("address", host);
        settings.put("port", port);
        settings.put("id", id);
        String scy = j.optString("scy");
        settings.put("security", scy.isEmpty() ? "auto" : scy);
        return finish(link, "vmess", host, port, settings, p, j.optString("ps"));
    }

    // ------------------------------------------------------------------ shadowsocks

    private static XrayLink fromShadowsocks(String link) throws JSONException {
        String name = null;
        String body = link.substring("ss://".length());
        int hash = body.indexOf('#');
        if (hash >= 0) {
            name = Uri.decode(body.substring(hash + 1));
            body = body.substring(0, hash);
        }
        String query = "";
        int q = body.indexOf('?');
        if (q >= 0) {
            query = body.substring(q + 1);
            body = body.substring(0, q);
        }
        if (body.endsWith("/")) body = body.substring(0, body.length() - 1);

        String method, password, host;
        int port;
        int at = body.lastIndexOf('@');
        if (at > 0) {
            // SIP002: userinfo is base64(method:password) or percent-encoded method:password
            String userInfo = Uri.decode(body.substring(0, at));
            if (!userInfo.contains(":")) userInfo = decodeBase64(userInfo);
            String hostPort = body.substring(at + 1);
            int colon = hostPort.lastIndexOf(':');
            if (colon <= 0) return null;
            host = stripBrackets(hostPort.substring(0, colon));
            port = parseInt(hostPort.substring(colon + 1));
            int c = userInfo.indexOf(':');
            if (c <= 0) return null;
            method = userInfo.substring(0, c);
            password = userInfo.substring(c + 1);
        } else {
            // legacy: base64(method:password@host:port)
            String all = decodeBase64(body);
            int at2 = all.lastIndexOf('@');
            int c = all.indexOf(':');
            if (at2 <= 0 || c <= 0 || c > at2) return null;
            method = all.substring(0, c);
            password = all.substring(c + 1, at2);
            String hostPort = all.substring(at2 + 1);
            int colon = hostPort.lastIndexOf(':');
            if (colon <= 0) return null;
            host = stripBrackets(hostPort.substring(0, colon));
            port = parseInt(hostPort.substring(colon + 1));
        }
        if (host.isEmpty() || port <= 0 || method.isEmpty()) return null;

        Map<String, String> p = new HashMap<>();
        p.put("type", "tcp");
        p.put("security", "none");
        if (!query.isEmpty()) {
            Map<String, String> qp = queryMap(Uri.parse("x://h/?" + query));
            String plugin = qp.get("plugin");
            if (plugin != null) {
                if (!plugin.contains("obfs=http")) return null; // other plugins aren't supported by Xray
                for (String pair : plugin.split(";")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length != 2) continue;
                    if ("obfs-host".equals(kv[0])) p.put("host", kv[1]);
                    if ("path".equals(kv[0])) p.put("path", kv[1]);
                }
                p.put("headerType", "http");
            }
        }

        JSONObject settings = new JSONObject();
        settings.put("address", host);
        settings.put("port", port);
        settings.put("method", method.toLowerCase(Locale.ROOT));
        settings.put("password", password);
        return finish(link, "shadowsocks", host, port, settings, p, name);
    }

    // ------------------------------------------------------------------ common: transport + security

    private static XrayLink finish(String link, String protocol, String host, int port, JSONObject settings,
                                   Map<String, String> p, String name) throws JSONException {
        String type = get(p, "type", "tcp");
        if ("raw".equals(type) || type.isEmpty()) type = "tcp";
        String security = get(p, "security", "none");
        if (security.isEmpty()) security = "none";

        JSONObject stream = new JSONObject();
        String hostHeader = get(p, "host", "");
        String path = get(p, "path", "/");
        if (path.isEmpty()) path = "/";
        String sniFromTransport = hostHeader;

        switch (type) {
            case "ws": {
                stream.put("network", "ws");
                stream.put("wsSettings", new JSONObject().put("path", path).put("host", hostHeader));
                break;
            }
            case "httpupgrade": {
                stream.put("network", "httpupgrade");
                stream.put("httpupgradeSettings", new JSONObject().put("path", path).put("host", hostHeader));
                break;
            }
            case "xhttp":
            case "splithttp": {
                type = "xhttp";
                stream.put("network", "xhttp");
                JSONObject xh = new JSONObject().put("path", path).put("host", hostHeader);
                String mode = get(p, "mode", "");
                if (!mode.isEmpty()) xh.put("mode", mode);
                String extra = get(p, "extra", "");
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
                stream.put("network", "grpc");
                JSONObject g = new JSONObject();
                g.put("serviceName", get(p, "serviceName", ""));
                g.put("multiMode", "multi".equals(get(p, "mode", "")));
                String authority = get(p, "authority", "");
                if (!authority.isEmpty()) g.put("authority", authority);
                sniFromTransport = authority;
                stream.put("grpcSettings", g);
                break;
            }
            case "tcp": {
                stream.put("network", "tcp");
                JSONObject header = new JSONObject();
                if ("http".equals(get(p, "headerType", ""))) {
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
                stream.put("tcpSettings", new JSONObject().put("header", header));
                break;
            }
            default:
                return null; // kcp, quic etc. – not supported here
        }

        if ("tls".equals(security) || "reality".equals(security)) {
            stream.put("security", security);
            JSONObject t = new JSONObject();
            String sni = get(p, "sni", "");
            if (sni.isEmpty()) sni = !sniFromTransport.isEmpty() ? sniFromTransport : (isIp(host) ? "" : host);
            if (!sni.isEmpty()) t.put("serverName", sni);
            String fp = get(p, "fp", "");
            if (fp.isEmpty() && "reality".equals(security)) fp = "chrome";
            if (!fp.isEmpty()) t.put("fingerprint", fp);
            String alpn = get(p, "alpn", "");
            if (!alpn.isEmpty()) {
                JSONArray a = new JSONArray();
                for (String s : alpn.split(",")) if (!s.trim().isEmpty()) a.put(s.trim());
                if (a.length() > 0) t.put("alpn", a);
            }
            if ("tls".equals(security)) {
                String ins = get(p, "insecure", get(p, "allowInsecure", ""));
                if ("1".equals(ins) || "true".equals(ins)) t.put("allowInsecure", true);
                stream.put("tlsSettings", t);
            } else {
                String pbk = get(p, "pbk", "");
                if (pbk.isEmpty()) return null; // reality without a key can't work
                t.put("publicKey", pbk);
                String sid = get(p, "sid", "");
                if (!sid.isEmpty()) t.put("shortId", sid);
                String spx = get(p, "spx", "");
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

        XrayLink l = new XrayLink(link);
        l.outbound = out;
        l.protocol = protocol;
        l.address = host;
        l.port = port;
        l.transport = type;
        l.security = security;
        l.name = TextUtils.isEmpty(name) ? host : name.trim();
        return l;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> queryMap(Uri u) {
        Map<String, String> m = new HashMap<>();
        try {
            for (String k : u.getQueryParameterNames()) {
                String v = u.getQueryParameter(k);
                if (v != null) m.put(k, v.trim());
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private static String get(Map<String, String> p, String key, String def) {
        String v = p.get(key);
        return v == null ? def : v.trim();
    }

    /** Accepts standard and URL-safe base64, with or without padding. */
    static String decodeBase64(String s) {
        String t = s.trim().replace('-', '+').replace('_', '/').replaceAll("\\s", "");
        while (t.length() % 4 != 0) t += "=";
        return new String(Base64.decode(t, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    private static String stripBrackets(String host) {
        if (host == null) return "";
        if (host.startsWith("[") && host.endsWith("]")) return host.substring(1, host.length() - 1);
        return host;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isIp(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }
}
