package com.revgiler.freevpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.util.concurrent.CopyOnWriteArrayList;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * VPN through Xray-core (VLESS / Trojan with TLS, Reality, XHTTP...).
 * Android's TUN file descriptor is handed straight to Xray's "tun" inbound.
 * Our own app is excluded from the tunnel, so Xray's connections to the server go out directly.
 */
public class XrayVpnService extends VpnService {

    public enum State {DISCONNECTED, CONNECTING, CONNECTED, FAILED}

    public interface Listener {
        void onXrayState(State state, String message);

        void onXrayTraffic(long down, long up);
    }

    public static final String ACTION_START = "com.revgiler.freevpn.XRAY_START";
    public static final String ACTION_STOP = "com.revgiler.freevpn.XRAY_STOP";
    public static final String EXTRA_CONFIG = "config";
    public static final String EXTRA_NAME = "name";

    private static final String CHANNEL = "freevpn_xray";
    private static final int NOTIFICATION_ID = 4242;

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile State state = State.DISCONNECTED;
    private static volatile String message = "";
    private static volatile String serverName = "";
    private static volatile long totalDown = 0, totalUp = 0;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor tun;
    private CoreController core;
    private Thread worker;

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

    public static void start(Context ctx, String config, String name) {
        Intent i = new Intent(ctx, XrayVpnService.class).setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config).putExtra(EXTRA_NAME, name);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        if (state == State.DISCONNECTED) return;
        Intent i = new Intent(ctx, XrayVpnService.class).setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Exception ignored) {
        }
    }

    private static void publish(State s, String msg) {
        state = s;
        message = msg == null ? "" : msg;
        for (Listener l : listeners) l.onXrayState(s, message);
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
            String config = intent.getStringExtra(EXTRA_CONFIG);
            serverName = intent.getStringExtra(EXTRA_NAME);
            startForegroundCompat("Подключение к " + serverName);
            launch(config);
        }
        return START_NOT_STICKY;
    }

    private void launch(String config) {
        stopCore();
        totalDown = 0;
        totalUp = 0;
        publish(State.CONNECTING, "Запуск Xray…");
        worker = new Thread(() -> {
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
                if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
                tun = b.establish();
                if (tun == null) throw new IllegalStateException("Нет разрешения на VPN");

                Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(), "");
                core = Libv2ray.newCoreController(new CoreCallbackHandler() {
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
                core.startLoop(config, tun.getFd());

                // Make sure the key really carries traffic before saying "connected".
                publish(State.CONNECTING, "Проверка соединения…");
                long delay = core.measureDelay(XrayConfig.TEST_URL);
                if (delay <= 0) throw new IllegalStateException("Сервер не пропускает трафик");

                publish(State.CONNECTED, delay + " мс");
                updateNotification("Подключено: " + serverName);
                pollTraffic();
            } catch (Throwable e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                stopCore();
                publish(State.FAILED, msg);
                stopForeground(true);
                stopSelf();
            }
        }, "xray-start");
        worker.start();
    }

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
        stopCore();
        if (state != State.FAILED) publish(State.DISCONNECTED, "");
        super.onDestroy();
    }

    // ------------------------------------------------------------------ notification

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "FreeVPN (VLESS)",
                    NotificationManager.IMPORTANCE_LOW));
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
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
