package com.revgiler.freevpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.service.quicksettings.TileService;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * VPN through Xray-core (VLESS / VMess / Trojan / Shadowsocks with TLS, Reality, XHTTP...).
 * Android's TUN file descriptor is handed straight to Xray's "tun" inbound.
 * Our own app (and apps the user excluded) bypass the tunnel.
 *
 * The service gets a list of working keys: if the current one stops passing traffic
 * (checked every 30 s), it switches to the next one by itself.
 */
public class XrayVpnService extends VpnService {

    public enum State {DISCONNECTED, CONNECTING, CONNECTED, FAILED}

    public interface Listener {
        void onXrayState(State state, String message);

        void onXrayTraffic(long down, long up);
    }

    public static final String ACTION_START = "com.revgiler.freevpn.XRAY_START";
    public static final String ACTION_STOP = "com.revgiler.freevpn.XRAY_STOP";
    public static final String EXTRA_CONFIGS = "configs";
    public static final String EXTRA_NAMES = "names";

    private static final String CHANNEL = "freevpn_xray";
    private static final int NOTIFICATION_ID = 4242;
    private static final long HEALTH_EVERY_MS = 30_000;
    private static final int HEALTH_FAILS_TO_SWITCH = 2;
    private static final String PREFS = "freevpn_xray_last";

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile State state = State.DISCONNECTED;
    private static volatile String message = "";
    private static volatile String serverName = "";
    private static volatile long totalDown = 0, totalUp = 0;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor tun;
    private CoreController core;
    private final ArrayList<String> configs = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private int index = 0;
    private int healthFails = 0;
    private volatile int generation = 0;

    // ------------------------------------------------------------------ public helpers

    public static void addListener(Listener l) {
        listeners.add(l);
        l.onXrayState(state, message);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static State getState() {
        return state;
    }

    public static String getServerName() {
        return serverName;
    }

    /** Start with a list of keys (best first); the service falls back to the next ones by itself. */
    public static void start(Context ctx, ArrayList<String> configs, ArrayList<String> names) {
        Intent i = new Intent(ctx, XrayVpnService.class).setAction(ACTION_START)
                .putStringArrayListExtra(EXTRA_CONFIGS, configs).putStringArrayListExtra(EXTRA_NAMES, names);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    /** Reconnect with the keys used last time (quick settings tile). */
    public static boolean startLast(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            JSONArray c = new JSONArray(p.getString("configs", "[]"));
            JSONArray n = new JSONArray(p.getString("names", "[]"));
            if (c.length() == 0) return false;
            ArrayList<String> cl = new ArrayList<>(), nl = new ArrayList<>();
            for (int i = 0; i < c.length(); i++) {
                cl.add(c.getString(i));
                nl.add(i < n.length() ? n.getString(i) : "VLESS");
            }
            start(ctx, cl, nl);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasLast(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString("configs", "[]").length() > 2;
    }

    public static void stop(Context ctx) {
        if (state == State.DISCONNECTED) return;
        Intent i = new Intent(ctx, XrayVpnService.class).setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Exception ignored) {
        }
    }

    private void publish(State s, String msg) {
        state = s;
        message = msg == null ? "" : msg;
        for (Listener l : listeners) l.onXrayState(s, message);
        try {
            TileService.requestListeningState(this, new ComponentName(this, VpnTileService.class));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ service

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            shutdown("Отключено");
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            ArrayList<String> c = intent.getStringArrayListExtra(EXTRA_CONFIGS);
            ArrayList<String> n = intent.getStringArrayListExtra(EXTRA_NAMES);
            if (c == null || c.isEmpty()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            configs.clear();
            configs.addAll(c);
            names.clear();
            if (n != null) names.addAll(n);
            while (names.size() < configs.size()) names.add("VLESS");
            saveLast();
            index = 0;
            startForegroundCompat("Подключение…");
            launchCurrent();
        }
        return START_NOT_STICKY;
    }

    private void saveLast() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("configs", new JSONArray(configs).toString())
                .putString("names", new JSONArray(names).toString())
                .apply();
    }

    private void launchCurrent() {
        final int gen = ++generation;
        final String config = configs.get(index);
        serverName = names.get(index);
        stopCore();
        totalDown = 0;
        totalUp = 0;
        healthFails = 0;
        publish(State.CONNECTING, index == 0 ? "Запуск…" : "Переключаюсь на запасной ключ…");
        updateNotification("Подключение: " + serverName);
        new Thread(() -> {
            try {
                Builder b = new Builder()
                        .setSession("FreeVPN")
                        .setMtu(XrayConfig.MTU)
                        .addAddress("10.10.14.1", 30)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer("1.1.1.1")
                        .addAddress("fc00::10:10:14:1", 126)
                        .addRoute("::", 0);
                b.addDisallowedApplication(getPackageName());
                for (String app : FvSettings.excludedApps(this)) {
                    try {
                        b.addDisallowedApplication(app);
                    } catch (Exception ignored) {
                        // app was uninstalled
                    }
                }
                if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
                ParcelFileDescriptor newTun = b.establish();
                if (newTun == null) throw new IllegalStateException("Нет разрешения на VPN");
                if (gen != generation) {
                    newTun.close();
                    return;
                }
                tun = newTun;

                Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(), "");
                CoreController c = Libv2ray.newCoreController(new CoreCallbackHandler() {
                    @Override
                    public long startup() {
                        return 0;
                    }

                    @Override
                    public long shutdown() {
                        return 0;
                    }

                    @Override
                    public long onEmitStatus(long code, String s) {
                        return 0;
                    }
                });
                core = c;
                c.startLoop(config, tun.getFd());

                publish(State.CONNECTING, "Проверка соединения…");
                long delay = c.measureDelay(XrayConfig.TEST_URL);
                if (gen != generation) return;
                if (delay <= 0) throw new IllegalStateException("Ключ не пропускает трафик");

                publish(State.CONNECTED, delay + " мс");
                updateNotification("Подключено: " + serverName);
                main.post(this::pollTraffic);
                main.postDelayed(healthCheck, HEALTH_EVERY_MS);
            } catch (Throwable e) {
                if (gen != generation) return;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                main.post(() -> nextOrFail(msg));
            }
        }, "xray-start").start();
    }

    /** Current key failed: take the next one from the list, or give up. */
    private void nextOrFail(String reason) {
        if (index + 1 < configs.size()) {
            index++;
            launchCurrent();
        } else {
            stopCore();
            publish(State.FAILED, reason);
            stopForeground(true);
            stopSelf();
        }
    }

    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            final CoreController c = core;
            final int gen = generation;
            if (c == null || state != State.CONNECTED) return;
            new Thread(() -> {
                long d;
                try {
                    d = c.measureDelay(XrayConfig.TEST_URL);
                } catch (Throwable e) {
                    d = -1;
                }
                final long delay = d;
                main.post(() -> {
                    if (gen != generation || state != State.CONNECTED) return;
                    if (delay > 0) {
                        healthFails = 0;
                    } else if (++healthFails >= HEALTH_FAILS_TO_SWITCH) {
                        nextOrFail("Ключ перестал работать");
                        return;
                    }
                    main.postDelayed(healthCheck, HEALTH_EVERY_MS);
                });
            }, "xray-health").start();
        }
    };

    private void pollTraffic() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                CoreController c = core;
                if (c == null || state != State.CONNECTED) return;
                try {
                    String stats = c.queryAllOutboundTrafficStats();
                    if (stats != null) {
                        for (String item : stats.split(";")) {
                            String[] p = item.split(",");
                            if (p.length != 3 || !"proxy".equals(p[0])) continue;
                            long v = Long.parseLong(p[2]);
                            if ("downlink".equals(p[1])) totalDown += v;
                            else if ("uplink".equals(p[1])) totalUp += v;
                        }
                    }
                } catch (Throwable ignored) {
                }
                for (Listener l : listeners) l.onXrayTraffic(totalDown, totalUp);
                main.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void stopCore() {
        main.removeCallbacksAndMessages(null);
        CoreController c = core;
        core = null;
        if (c != null) {
            try {
                c.stopLoop();
            } catch (Throwable ignored) {
            }
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (Exception ignored) {
            }
            tun = null;
        }
    }

    private void shutdown(String msg) {
        generation++;
        stopCore();
        publish(State.DISCONNECTED, msg);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        shutdown("VPN отключён системой");
    }

    @Override
    public void onDestroy() {
        generation++;
        stopCore();
        if (state != State.FAILED && state != State.DISCONNECTED) publish(State.DISCONNECTED, "");
        super.onDestroy();
    }

    // ------------------------------------------------------------------ notification

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "FreeVPN (VLESS)",
                    NotificationManager.IMPORTANCE_LOW));
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, XrayVpnService.class).setAction(ACTION_STOP), piFlags);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return nb.setContentTitle("FreeVPN")
                .setContentText(text)
                .setSmallIcon(de.blinkt.openvpn.R.drawable.freevpn_icon)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Отключить", stop).build())
                .build();
    }

    private void startForegroundCompat(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
