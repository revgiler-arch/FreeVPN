package com.revgiler.freevpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Self-update from GitHub Releases: every build publishes FreeVPN.apk and version.json to the
 * "latest" release. On start we compare versionCode and offer to install the new APK
 * (downloaded inside the app and handed to Android's PackageInstaller).
 */
public final class Updater {
    private static final String BASE = "https://github.com/revgiler-arch/FreeVPN/releases/latest/download/";
    private static final String VERSION_URL = BASE + "version.json";
    private static final String APK_URL = BASE + "FreeVPN.apk";
    private static final long CHECK_EVERY_MS = 3 * 60 * 60 * 1000L; // automatic check at most every 3 h
    private static final String PREFS = "freevpn_update";

    private Updater() {
    }

    /** @param manual true when the user asked: always check and report "no update" too. */
    public static void check(Activity a, boolean manual) {
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - p.getLong("last_check", 0) < CHECK_EVERY_MS) return;
        p.edit().putLong("last_check", now).apply();
        if (manual) Toast.makeText(a, "Проверяю обновления…", Toast.LENGTH_SHORT).show();

        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(readText(VERSION_URL + "?t=" + now));
                int remote = j.getInt("versionCode");
                String name = j.optString("versionName", "?");
                String notes = j.optString("notes", "");
                int local = currentVersionCode(a);
                ui.post(() -> {
                    if (a.isFinishing()) return;
                    if (remote > local) {
                        offer(a, name, notes);
                    } else if (manual) {
                        Toast.makeText(a, "У тебя последняя версия", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                if (manual) ui.post(() -> Toast.makeText(a, "Не удалось проверить обновления: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }, "update-check").start();
    }

    private static void offer(Activity a, String versionName, String notes) {
        String msg = "Доступна версия " + versionName + "."
                + (notes.isEmpty() ? "" : "\n\n" + notes)
                + "\n\nОбновление скачается (~45 МБ) и установится поверх, настройки и ключи сохранятся.";
        new AlertDialog.Builder(a)
                .setTitle("Обновление FreeVPN")
                .setMessage(msg)
                .setPositiveButton("Обновить", (d, w) -> download(a))
                .setNegativeButton("Позже", null)
                .show();
    }

    private static void download(Activity a) {
        AlertDialog progress = new AlertDialog.Builder(a)
                .setTitle("Скачиваю обновление")
                .setMessage("0%")
                .setCancelable(false)
                .show();
        Handler ui = new Handler(Looper.getMainLooper());
        Context app = a.getApplicationContext();
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                int id = installer.createSession(params);
                session = installer.openSession(id);

                HttpURLConnection c = open(APK_URL);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream();
                     OutputStream out = session.openWrite("FreeVPN.apk", 0, total > 0 ? total : -1)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                ui.post(() -> progress.setMessage(pct + "%"));
                            }
                        }
                    }
                    session.fsync(out);
                } finally {
                    c.disconnect();
                }

                Intent cb = new Intent(app, InstallReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(app, id, cb, flags);
                session.commit(pi.getIntentSender());
                session.close();
                session = null;
                ui.post(() -> {
                    progress.dismiss();
                    Toast.makeText(app, "Подтверди установку", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                if (session != null) session.abandon();
                ui.post(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(a)
                            .setTitle("Не удалось обновить")
                            .setMessage(e.getMessage() + "\n\nМожно скачать вручную: github.com/revgiler-arch/FreeVPN/releases/latest")
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }, "update-download").start();
    }

    /** Gets PackageInstaller results; shows the system "Install?" screen when Android asks for it. */
    public static class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(ctx, "Обновление не установлено" + (m != null ? ": " + m : ""), Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int currentVersionCode(Context ctx) throws PackageManager.NameNotFoundException {
        return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
    }

    private static HttpURLConnection open(String url) throws IOException {
        // GitHub answers with redirects to its download servers; follow them by hand to be safe.
        String current = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "FreeVPN-Android");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Пустой redirect");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            return c;
        }
        throw new IOException("Слишком много перенаправлений");
    }

    private static String readText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
