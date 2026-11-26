package com.revgiler.freevpn;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.LogItem;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;
import de.blinkt.openvpn.core.VpnStatus;
import libv2ray.Libv2ray;

/**
 * FreeVPN main screen. Two modes:
 *  - VLESS (Xray): free VLESS/Trojan keys (or your own server) – disguised as HTTPS, works on 4G
 *  - VPN Gate (OpenVPN): free volunteer servers – fine on Wi-Fi, often blocked on mobile networks
 */
public class MainActivity extends Activity implements VpnStatus.StateListener, VpnStatus.ByteCountListener,
        VpnStatus.LogListener, XrayVpnService.Listener {

    private static final String MODE_VLESS = "vless", MODE_GATE = "gate";
    private static final String PROFILE_PREFIX = "FreeVPN: ";
    private static final int REQ_VPN_PERMISSION = 70;
    private static final int REQ_NOTIFICATIONS = 71;
    // VPN Gate
    private static final long ATTEMPT_TIMEOUT_MS = 20_000;
    private static final long NO_REPLY_TIMEOUT_MS = 10_000;
    private static final int MAX_ATTEMPTS = 8;
    // VLESS
    private static final int TEST_THREADS = 12;
    private static final int MAX_CANDIDATES = 60;
    private static final int LOG_LINES = 120;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ExecutorService testers;

    private View connectButton;
    private TextView connectText, statusText, serverInfo, trafficText, listHint, tabVless, tabGate, addKey, footer;
    private Spinner countrySpinner;
    private ListView serverList;
    private TextView logView, logToggle;
    private ScrollView logScroll;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    private String mode = MODE_VLESS;
    private SharedPreferences prefs;

    // ---- VPN Gate state
    private final List<VpnServer> allServers = new ArrayList<>();
    private final List<VpnServer> shownServers = new ArrayList<>();
    private String selectedCountry = null;
    private VpnServer selectedServer = null;
    private final List<VpnServer> queue = new ArrayList<>();
    private VpnServer currentServer = null;
    private int attempts = 0;
    private boolean gateWantsConnection = false;
    private ConnectionStatus lastLevel = ConnectionStatus.LEVEL_NOTCONNECTED;

    // ---- VLESS state
    private final List<XrayLink> keys = new ArrayList<>();
    private XrayLink selectedKey = null;
    private XrayLink currentKey = null;
    private boolean vlessWantsConnection = false;
    private XrayVpnService.State xrayState = XrayVpnService.State.DISCONNECTED;
    private final List<XrayLink> working = new ArrayList<>(); // tested OK, best first
    private final List<XrayLink> triedThisRound = new ArrayList<>();
    private int searchGeneration = 0;

    private RowAdapter adapter;

    private IOpenVPNServiceInternal service;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IOpenVPNServiceInternal.Stub.asInterface(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final Runnable noReplyTimeout = () -> {
        if (gateWantsConnection && lastLevel == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET) {
            tryNextServer("Сервер недоступен");
        }
    };

    private final Runnable attemptTimeout = () -> {
        if (gateWantsConnection && lastLevel != ConnectionStatus.LEVEL_CONNECTED) {
            tryNextServer("Сервер не ответил");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.freevpn_main);
        prefs = getPreferences(MODE_PRIVATE);
        mode = prefs.getString("mode", MODE_VLESS);

        connectButton = findViewById(R.id.fv_connect);
        connectText = findViewById(R.id.fv_connect_text);
        statusText = findViewById(R.id.fv_status);
        serverInfo = findViewById(R.id.fv_server_info);
        trafficText = findViewById(R.id.fv_traffic);
        listHint = findViewById(R.id.fv_list_hint);
        countrySpinner = findViewById(R.id.fv_country);
        serverList = findViewById(R.id.fv_servers);
        tabVless = findViewById(R.id.fv_tab_vless);
        tabGate = findViewById(R.id.fv_tab_gate);
        addKey = findViewById(R.id.fv_add_key);
        footer = findViewById(R.id.fv_footer);
        logView = findViewById(R.id.fv_log);
        logToggle = findViewById(R.id.fv_log_toggle);
        logScroll = findViewById(R.id.fv_log_scroll);

        adapter = new RowAdapter();
        serverList.setAdapter(adapter);
        serverList.setOnItemClickListener((parent, view, position, id) -> onRowClicked(position));
        serverList.setOnItemLongClickListener((parent, view, position, id) -> onRowLongClicked(position));

        connectButton.setOnClickListener(v -> onConnectClicked());
        findViewById(R.id.fv_refresh).setOnClickListener(v -> loadCurrent());
        addKey.setOnClickListener(v -> showAddKeyDialog());
        findViewById(R.id.fv_settings).setOnClickListener(v -> showSettings());
        tabVless.setOnClickListener(v -> switchMode(MODE_VLESS));
        tabGate.setOnClickListener(v -> switchMode(MODE_GATE));

        logToggle.setOnClickListener(v -> {
            boolean show = logScroll.getVisibility() != View.VISIBLE;
            logScroll.setVisibility(show ? View.VISIBLE : View.GONE);
            logToggle.setText(show ? "Журнал ▴  (долгое нажатие — копировать)" : "Журнал ▾");
            if (show) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
        logToggle.setOnLongClickListener(v -> {
            copyLog();
            return true;
        });

        // Tap the title = check for an update now; also checked automatically on start.
        findViewById(R.id.fv_title).setOnClickListener(v -> Updater.check(this, true));
        Updater.check(this, false);

        askNotificationPermission();
        // If a VLESS connection is already running, open in that mode.
        if (XrayVpnService.getState() != XrayVpnService.State.DISCONNECTED) mode = MODE_VLESS;
        applyMode(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, OpenVPNService.class);
        intent.setAction(OpenVPNService.START_SERVICE);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        VpnStatus.addStateListener(this);
        VpnStatus.addByteCountListener(this);
        VpnStatus.addLogListener(this);
        XrayVpnService.addListener(this);
    }

    @Override
    protected void onStop() {
        VpnStatus.removeStateListener(this);
        VpnStatus.removeByteCountListener(this);
        VpnStatus.removeLogListener(this);
        XrayVpnService.removeListener(this);
        try {
            unbindService(serviceConnection);
        } catch (IllegalArgumentException ignored) {
        }
        service = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (testers != null) testers.shutdownNow();
        super.onDestroy();
    }

    // =================================================================== modes

    private boolean isVless() {
        return MODE_VLESS.equals(mode);
    }

    private boolean anythingActive() {
        return gateWantsConnection || lastLevel == ConnectionStatus.LEVEL_CONNECTED
                || vlessWantsConnection || xrayState != XrayVpnService.State.DISCONNECTED
                && xrayState != XrayVpnService.State.FAILED;
    }

    private void switchMode(String newMode) {
        if (newMode.equals(mode)) return;
        if (anythingActive()) {
            Toast.makeText(this, "Сначала отключись", Toast.LENGTH_SHORT).show();
            return;
        }
        mode = newMode;
        prefs.edit().putString("mode", mode).apply();
        applyMode(true);
    }

    private void applyMode(boolean load) {
        tabVless.setSelected(isVless());
        tabGate.setSelected(!isVless());
        addKey.setVisibility(isVless() ? View.VISIBLE : View.GONE);
        countrySpinner.setVisibility(isVless() ? View.GONE : View.VISIBLE);
        footer.setText((isVless()
                ? "Бесплатные ключи: github.com/igareck/vpn-configs-for-russia · свой ключ или подписка: «＋ Ключ»"
                : "Бесплатные серверы добровольцев VPN Gate (vpngate.net)") + "\nВерсия " + versionName()
                + " · нажми на «FreeVPN», чтобы проверить обновления");
        statusText.setText("Не подключено");
        trafficText.setText("");
        renderButton();
        updateServerInfo();
        adapter.notifyDataSetChanged();
        if (load) {
            boolean empty = isVless() ? keys.isEmpty() : allServers.isEmpty();
            if (empty) loadCurrent();
            else updateHint(false);
        }
    }

    private void loadCurrent() {
        if (isVless()) loadKeys();
        else loadServers();
    }

    // =================================================================== list

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return isVless() ? keys.size() : shownServers.size();
        }

        @Override
        public Object getItem(int position) {
            return isVless() ? keys.get(position) : shownServers.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.freevpn_server_row, parent, false);
            TextView flag = v.findViewById(R.id.fv_row_flag);
            TextView title = v.findViewById(R.id.fv_row_title);
            TextView sub = v.findViewById(R.id.fv_row_sub);
            TextView ping = v.findViewById(R.id.fv_row_ping);
            if (isVless()) {
                XrayLink k = keys.get(position);
                flag.setText(k.userKey() ? "⭐" : "🔑");
                flag.setVisibility(startsWithEmoji(k.name) ? View.GONE : View.VISIBLE);
                title.setText(k.name);
                sub.setText(k.subtitle());
                if (k.delayMs > 0) {
                    ping.setText(k.delayMs + " мс");
                    ping.setTextColor(Color.parseColor("#2ECC71"));
                } else if (k.delayMs < 0) {
                    ping.setText("✕");
                    ping.setTextColor(Color.parseColor("#E5534B"));
                } else {
                    ping.setText("—");
                    ping.setTextColor(Color.parseColor("#8B93A1"));
                }
                boolean sel = selectedKey != null && selectedKey.id().equals(k.id());
                boolean act = currentKey != null && currentKey.id().equals(k.id())
                        && xrayState == XrayVpnService.State.CONNECTED;
                v.setActivated(sel || act);
            } else {
                VpnServer s = shownServers.get(position);
                flag.setVisibility(View.VISIBLE);
                flag.setText(s.flag());
                title.setText(s.countryLong + " · " + s.hostName);
                sub.setText(s.speedText() + " · сессий: " + s.sessions);
                ping.setText(s.pingText());
                ping.setTextColor(Color.parseColor("#2ECC71"));
                boolean selected = selectedServer != null && selectedServer.id().equals(s.id());
                boolean active = currentServer != null && currentServer.id().equals(s.id())
                        && lastLevel == ConnectionStatus.LEVEL_CONNECTED;
                v.setActivated(selected || active);
            }
            return v;
        }
    }

    private static boolean startsWithEmoji(String s) {
        if (s == null || s.isEmpty()) return false;
        int cp = s.codePointAt(0);
        return cp >= 0x1F000 || (cp >= 0x2600 && cp <= 0x27BF);
    }

    private void onRowClicked(int position) {
        if (isVless()) {
            XrayLink k = keys.get(position);
            selectedKey = (selectedKey != null && selectedKey.id().equals(k.id())) ? null : k;
        } else {
            VpnServer s = shownServers.get(position);
            selectedServer = (selectedServer != null && selectedServer.id().equals(s.id())) ? null : s;
        }
        adapter.notifyDataSetChanged();
        updateServerInfo();
    }

    private boolean onRowLongClicked(int position) {
        if (!isVless()) return false;
        XrayLink k = keys.get(position);
        if (k.source == XrayLink.Source.SUBSCRIPTION) {
            new AlertDialog.Builder(this)
                    .setTitle("Подписка")
                    .setMessage(k.subscription)
                    .setPositiveButton("Удалить подписку", (d, w) -> {
                        XrayRepo.removeSubscription(this, k.subscription);
                        List<XrayLink> keep = new ArrayList<>();
                        for (XrayLink x : keys) if (!k.subscription.equals(x.subscription)) keep.add(x);
                        keys.clear();
                        keys.addAll(keep);
                        working.retainAll(keep);
                        if (selectedKey != null && !keep.contains(selectedKey)) selectedKey = null;
                        adapter.notifyDataSetChanged();
                        updateHint(false);
                    })
                    .setNeutralButton("Копировать ключ", (d, w) -> copyKey(k))
                    .setNegativeButton("Отмена", null)
                    .show();
            return true;
        }
        if (!k.userKey()) {
            copyKey(k);
            return true;
        }
        new AlertDialog.Builder(this)
                .setTitle("Удалить свой ключ?")
                .setMessage(k.name)
                .setPositiveButton("Удалить", (d, w) -> {
                    XrayRepo.removeUserKey(this, k.link);
                    keys.remove(k);
                    if (selectedKey == k) selectedKey = null;
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("Отмена", null)
                .show();
        return true;
    }

    private void copyKey(XrayLink k) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("key", k.link));
        Toast.makeText(this, "Ключ скопирован", Toast.LENGTH_SHORT).show();
    }

    private void updateHint(boolean fromCache) {
        if (isVless()) {
            int ok = 0;
            for (XrayLink k : keys) if (k.delayMs > 0) ok++;
            listHint.setText((fromCache ? "Нет связи — показан прошлый список. " : "")
                    + "Ключей: " + keys.size() + (ok > 0 ? ", рабочих: " + ok : "")
                    + ". «Подключить» сам найдёт рабочий ключ.");
        } else {
            listHint.setText((fromCache ? "Нет связи с VPN Gate — показан прошлый список. " : "")
                    + "Серверов: " + shownServers.size()
                    + ". Нажми на сервер, чтобы выбрать, или просто «Подключить».");
        }
    }

    // =================================================================== VLESS: keys

    private void loadKeys() {
        listHint.setText("Загрузка ключей…");
        io.execute(() -> {
            try {
                XrayRepo.Result r = XrayRepo.load(getApplicationContext(), (n, total) ->
                        ui.post(() -> listHint.setText("Загрузка ключей… (источник " + n + " из " + total + ")")));
                ui.post(() -> {
                    keys.clear();
                    keys.addAll(r.links);
                    working.clear();
                    selectedKey = null;
                    if (isVless()) {
                        adapter.notifyDataSetChanged();
                        updateHint(r.fromCache);
                    }
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    if (isVless()) listHint.setText("Не удалось загрузить ключи: " + e.getMessage()
                            + "\nНажми «Обновить» или добавь свой ключ.");
                });
            }
        });
    }

    private void showAddKeyDialog() {
        EditText input = new EditText(this);
        input.setHint("vless:// vmess:// ss:// trojan://\nили ссылка подписки https://…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
            if (clip != null && clip.toString().contains("://")) input.setText(clip);
        }
        new AlertDialog.Builder(this)
                .setTitle("Добавить ключ или подписку")
                .setMessage("Ключ — ссылка на один сервер (свой сервер или из бота).\n"
                        + "Подписка — ссылка https://… от VPN-сервиса: все её ключи загрузятся и будут обновляться сами.")
                .setView(input)
                .setPositiveButton("Добавить", (d, w) -> {
                    int[] n = XrayRepo.addUserInput(this, input.getText().toString());
                    if (n[0] == 0 && n[1] == 0) {
                        Toast.makeText(this, "Не нашёл ни ключа, ни ссылки подписки", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (n[1] > 0) {
                        Toast.makeText(this, "Подписка добавлена, загружаю ключи…", Toast.LENGTH_SHORT).show();
                        selectedKey = null;
                        loadKeys();
                        return;
                    }
                    Toast.makeText(this, "Добавлено ключей: " + n[0], Toast.LENGTH_SHORT).show();
                    List<XrayLink> mine = XrayRepo.userKeys(this);
                    List<XrayLink> rest = new ArrayList<>();
                    for (XrayLink k : keys) if (!k.userKey()) rest.add(k);
                    keys.clear();
                    keys.addAll(mine);
                    keys.addAll(rest);
                    selectedKey = mine.isEmpty() ? null : mine.get(mine.size() - 1);
                    adapter.notifyDataSetChanged();
                    updateServerInfo();
                    updateHint(false);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // =================================================================== settings

    private void showSettings() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        android.widget.CheckBox ru = new android.widget.CheckBox(this);
        ru.setText("Российские сайты напрямую\n(.ru, .рф, Яндекс, VK, банки — без VPN; только VLESS)");
        ru.setChecked(FvSettings.ruDirect(this));
        box.addView(ru);

        android.widget.CheckBox dpi = new android.widget.CheckBox(this);
        dpi.setText("Обход DPI\n(режет начало соединения на кусочки; включи, если ключи не отвечают)");
        dpi.setChecked(FvSettings.antiDpi(this));
        box.addView(dpi);

        android.widget.Button apps = new android.widget.Button(this);
        apps.setText("Приложения без VPN (" + FvSettings.excludedApps(this).size() + ")");
        apps.setOnClickListener(v -> showAppPicker(apps));
        box.addView(apps);

        TextView note = new TextView(this);
        note.setText("\nЕсли ключ перестанет работать, приложение само переключится на запасной.\n"
                + "Плитка «FreeVPN» есть в шторке: Быстрые настройки → карандаш → перетащи плитку.");
        note.setTextSize(12);
        box.addView(note);

        new AlertDialog.Builder(this)
                .setTitle("Настройки")
                .setView(box)
                .setPositiveButton("Готово", (d, w) -> {
                    boolean changed = ru.isChecked() != FvSettings.ruDirect(this)
                            || dpi.isChecked() != FvSettings.antiDpi(this);
                    FvSettings.setRuDirect(this, ru.isChecked());
                    FvSettings.setAntiDpi(this, dpi.isChecked());
                    if (changed) {
                        // test results depend on these settings
                        for (XrayLink k : keys) k.delayMs = 0;
                        working.clear();
                        adapter.notifyDataSetChanged();
                    }
                    if (anythingActive()) {
                        Toast.makeText(this, "Применится при следующем подключении", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void showAppPicker(android.widget.Button counter) {
        Toast.makeText(this, "Загружаю список приложений…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.TreeMap<String, String> byLabel = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                byLabel.put(ri.loadLabel(pm).toString() + "\u0000" + pkg, pkg);
            }
            List<String> labels = new ArrayList<>();
            List<String> pkgs = new ArrayList<>();
            for (Map.Entry<String, String> e : byLabel.entrySet()) {
                labels.add(e.getKey().substring(0, e.getKey().indexOf('\u0000')));
                pkgs.add(e.getValue());
            }
            java.util.Set<String> chosen = FvSettings.excludedApps(this);
            boolean[] checked = new boolean[pkgs.size()];
            for (int i = 0; i < pkgs.size(); i++) checked[i] = chosen.contains(pkgs.get(i));
            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle("Эти приложения работают без VPN")
                    .setMultiChoiceItems(labels.toArray(new String[0]), checked, (d, which, isChecked) -> {
                        if (isChecked) chosen.add(pkgs.get(which));
                        else chosen.remove(pkgs.get(which));
                    })
                    .setPositiveButton("Сохранить", (d, w) -> {
                        FvSettings.setExcludedApps(this, chosen);
                        counter.setText("Приложения без VPN (" + chosen.size() + ")");
                    })
                    .setNegativeButton("Отмена", null)
                    .show());
        });
    }

    // =================================================================== VLESS: connect

    private void startVless() {
        if (keys.isEmpty()) {
            Toast.makeText(this, "Ключи ещё не загружены", Toast.LENGTH_SHORT).show();
            loadKeys();
            return;
        }
        vlessWantsConnection = true;
        triedThisRound.clear();
        renderButton();
        searchAndConnect();
    }

    /** Test keys in parallel; connect to the first one that answers. */
    private void searchAndConnect() {
        if (!vlessWantsConnection) return;
        // Already-proven keys first.
        for (XrayLink w : new ArrayList<>(working)) {
            if (!triedThisRound.contains(w)) {
                connectKey(w);
                return;
            }
        }
        List<XrayLink> candidates = new ArrayList<>();
        if (selectedKey != null && !triedThisRound.contains(selectedKey)) candidates.add(selectedKey);
        for (XrayLink k : keys) {
            if (k.source != XrayLink.Source.FREE && !candidates.contains(k) && !triedThisRound.contains(k)) candidates.add(k);
        }
        for (XrayLink k : keys) {
            if (candidates.size() >= MAX_CANDIDATES) break;
            if (!candidates.contains(k) && !triedThisRound.contains(k) && k.delayMs >= 0) candidates.add(k);
        }
        if (candidates.isEmpty()) {
            vlessWantsConnection = false;
            statusText.setText("Рабочий ключ не найден");
            serverInfo.setText("Нажми «Обновить» и попробуй ещё раз, или добавь свой ключ");
            renderButton();
            return;
        }

        final int gen = ++searchGeneration;
        final int total = candidates.size();
        final AtomicInteger done = new AtomicInteger();
        final AtomicBoolean found = new AtomicBoolean(false);
        statusText.setText("Ищу рабочий ключ… 0 из " + total);
        serverInfo.setText("Проверяю бесплатные ключи");
        if (testers != null) testers.shutdownNow();
        testers = Executors.newFixedThreadPool(TEST_THREADS);
        for (XrayLink k : candidates) {
            testers.execute(() -> {
                if (found.get() || gen != searchGeneration) return;
                long delay;
                try {
                    delay = Libv2ray.measureOutboundDelay(XrayConfig.forTest(getApplicationContext(), k),
                            XrayConfig.TEST_URL);
                } catch (Throwable e) {
                    delay = -1;
                }
                final long d = delay > 0 ? delay : -1;
                ui.post(() -> {
                    if (gen != searchGeneration) return;
                    k.delayMs = d;
                    int n = done.incrementAndGet();
                    if (d > 0 && !working.contains(k)) {
                        working.add(k);
                        Collections.sort(working, (a, b) -> Long.compare(a.delayMs, b.delayMs));
                    }
                    adapter.notifyDataSetChanged();
                    if (!vlessWantsConnection) return;
                    if (d > 0 && found.compareAndSet(false, true)) {
                        connectKey(k);
                    } else if (!found.get()) {
                        statusText.setText("Ищу рабочий ключ… " + n + " из " + total);
                        if (n == total) {
                            for (XrayLink c : candidates) if (!triedThisRound.contains(c)) triedThisRound.add(c);
                            vlessWantsConnection = false;
                            statusText.setText("Ни один ключ не ответил");
                            serverInfo.setText("Нажми «Обновить» — список меняется каждый час");
                            renderButton();
                        }
                    }
                });
            });
        }
    }

    private void connectKey(XrayLink k) {
        if (!vlessWantsConnection) return;
        triedThisRound.add(k);
        currentKey = k;
        try {
            stopGateQuietly();
            // The chosen key first, then other proven keys as automatic fallbacks.
            ArrayList<String> configs = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            configs.add(XrayConfig.forVpn(this, k));
            names.add(k.name);
            for (XrayLink w : working) {
                if (configs.size() >= 6) break;
                if (w == k) continue;
                configs.add(XrayConfig.forVpn(this, w));
                names.add(w.name);
            }
            XrayVpnService.start(this, configs, names);
            statusText.setText("Подключение…");
            updateServerInfo();
        } catch (Exception e) {
            working.remove(k);
            searchAndConnect();
        }
    }

    @Override
    public void onXrayState(XrayVpnService.State state, String message) {
        ui.post(() -> {
            xrayState = state;
            if (!isVless()) return;
            switch (state) {
                case CONNECTING:
                    statusText.setText(message);
                    XrayLink now2 = findKeyByName(XrayVpnService.getServerName());
                    if (now2 != null) currentKey = now2;
                    break;
                case CONNECTED:
                    vlessWantsConnection = true;
                    XrayLink now = findKeyByName(XrayVpnService.getServerName());
                    if (now != null) currentKey = now;
                    statusText.setText("✅ Подключено · " + message);
                    break;
                case FAILED:
                    if (currentKey != null) {
                        working.remove(currentKey);
                        currentKey.delayMs = -1;
                    }
                    if (vlessWantsConnection) {
                        Toast.makeText(this, "Ключ не заработал (" + message + "), пробую другой…",
                                Toast.LENGTH_SHORT).show();
                        searchAndConnect();
                    } else {
                        statusText.setText("Ошибка: " + message);
                    }
                    break;
                case DISCONNECTED:
                    vlessWantsConnection = false;
                    statusText.setText("Не подключено");
                    trafficText.setText("");
                    break;
            }
            renderButton();
            updateServerInfo();
            adapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onXrayTraffic(long down, long up) {
        ui.post(() -> {
            if (isVless() && xrayState == XrayVpnService.State.CONNECTED) {
                trafficText.setText("↓ " + human(down) + "   ↑ " + human(up));
            }
        });
    }

    private XrayLink findKeyByName(String name) {
        for (XrayLink k : keys) if (k.name.equals(name)) return k;
        return null;
    }

    private void stopVless() {
        vlessWantsConnection = false;
        searchGeneration++;
        if (testers != null) testers.shutdownNow();
        XrayVpnService.stop(this);
        statusText.setText("Отключение…");
        renderButton();
        if (xrayState == XrayVpnService.State.DISCONNECTED || xrayState == XrayVpnService.State.FAILED) {
            statusText.setText("Не подключено");
        }
    }

    // =================================================================== VPN Gate: server list

    private void loadServers() {
        listHint.setText("Загрузка серверов…");
        io.execute(() -> {
            try {
                VpnGateApi.Result r = VpnGateApi.load(getApplicationContext(), (n, total) ->
                        ui.post(() -> listHint.setText("Загрузка серверов… (источник " + n + " из " + total + ")")));
                ui.post(() -> {
                    allServers.clear();
                    allServers.addAll(r.servers);
                    if (!isVless()) {
                        setupCountries();
                        applyFilter();
                        updateHint(r.fromCache);
                    }
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    if (!isVless()) listHint.setText("Не удалось загрузить список: " + e.getMessage()
                            + "\nПроверь интернет и нажми «Обновить».");
                });
            }
        });
    }

    private void setupCountries() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (VpnServer s : allServers) {
            Integer c = counts.get(s.countryShort);
            counts.put(s.countryShort, c == null ? 1 : c + 1);
            names.put(s.countryShort, s.flag() + "  " + s.countryLong);
        }
        final List<String> codes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        codes.add(null);
        labels.add("🌐  Все страны (" + allServers.size() + ")");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            codes.add(e.getKey());
            labels.add(names.get(e.getKey()) + " (" + e.getValue() + ")");
        }
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextColor(Color.parseColor("#E8EAED"));
                return t;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getDropDownView(position, convertView, parent);
                t.setTextColor(Color.parseColor("#E8EAED"));
                return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        countrySpinner.setAdapter(a);
        int keep = selectedCountry == null ? 0 : Math.max(0, codes.indexOf(selectedCountry));
        countrySpinner.setSelection(keep);
        countrySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedCountry = codes.get(position);
                applyFilter();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void applyFilter() {
        shownServers.clear();
        for (VpnServer s : allServers) {
            if (selectedCountry == null || selectedCountry.equals(s.countryShort)) shownServers.add(s);
        }
        if (selectedServer != null && !shownServers.contains(selectedServer)) selectedServer = null;
        adapter.notifyDataSetChanged();
        updateServerInfo();
    }

    // =================================================================== connect button

    private void onConnectClicked() {
        if (isVless()) {
            if (vlessWantsConnection || xrayState == XrayVpnService.State.CONNECTED
                    || xrayState == XrayVpnService.State.CONNECTING) {
                stopVless();
                return;
            }
        } else if (gateWantsConnection || lastLevel == ConnectionStatus.LEVEL_CONNECTED) {
            disconnectGate();
            return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission != null) {
            try {
                startActivityForResult(permission, REQ_VPN_PERMISSION);
            } catch (Exception e) {
                Toast.makeText(this, "Android не дал открыть запрос VPN", Toast.LENGTH_LONG).show();
            }
        } else {
            startSelectedMode();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) startSelectedMode();
            else Toast.makeText(this, "Без разрешения VPN подключиться нельзя", Toast.LENGTH_LONG).show();
        }
    }

    private void startSelectedMode() {
        if (isVless()) startVless();
        else startGate();
    }

    // =================================================================== VPN Gate: connect

    private void startGate() {
        if (allServers.isEmpty()) {
            Toast.makeText(this, "Список серверов ещё не загружен", Toast.LENGTH_SHORT).show();
            loadServers();
            return;
        }
        XrayVpnService.stop(this);
        queue.clear();
        if (selectedServer != null) queue.add(selectedServer);
        for (VpnServer s : shownServers) {
            if (queue.size() >= MAX_ATTEMPTS) break;
            if (!queue.contains(s)) queue.add(s);
        }
        attempts = 0;
        gateWantsConnection = true;
        tryNextServer(null);
    }

    private void tryNextServer(String reasonForSkip) {
        ui.removeCallbacks(attemptTimeout);
        ui.removeCallbacks(noReplyTimeout);
        if (!gateWantsConnection) return;
        if (queue.isEmpty()) {
            gateWantsConnection = false;
            stopGateQuietly();
            statusText.setText("Не удалось подключиться");
            serverInfo.setText("На мобильном интернете попробуй режим VLESS");
            renderButton();
            return;
        }
        if (reasonForSkip != null && currentServer != null) {
            Toast.makeText(this, reasonForSkip + " (" + currentServer.countryLong + "), пробую следующий…",
                    Toast.LENGTH_SHORT).show();
        }
        currentServer = queue.remove(0);
        attempts++;
        try {
            launchGate(currentServer);
            statusText.setText("Подключение… (попытка " + attempts + ")");
            updateServerInfo();
            renderButton();
            ui.postDelayed(attemptTimeout, ATTEMPT_TIMEOUT_MS);
            ui.postDelayed(noReplyTimeout, NO_REPLY_TIMEOUT_MS);
        } catch (Exception e) {
            tryNextServer("Ошибка конфигурации");
        }
    }

    private void launchGate(VpnServer s) throws Exception {
        ConfigParser parser = new ConfigParser();
        parser.parseConfig(new StringReader(VpnGateApi.config(s)));
        VpnProfile profile = parser.convertProfile();
        profile.mName = PROFILE_PREFIX + s.countryLong + " " + s.hostName;
        // apps the user wants outside the VPN
        profile.mAllowedAppsVpn = new java.util.HashSet<>(FvSettings.excludedApps(this));
        profile.mAllowedAppsVpnAreDisallowed = true;

        ProfileManager pm = ProfileManager.getInstance(this);
        for (VpnProfile old : new ArrayList<>(pm.getProfiles())) {
            if (old.mName != null && old.mName.startsWith(PROFILE_PREFIX)) pm.removeProfile(this, old);
        }
        pm.addProfile(profile);
        ProfileManager.saveProfile(this, profile);
        pm.saveProfileList(this);

        VPNLaunchHelper.startOpenVpn(profile, getBaseContext(), "FreeVPN", true);
    }

    private void disconnectGate() {
        gateWantsConnection = false;
        queue.clear();
        ui.removeCallbacks(attemptTimeout);
        ui.removeCallbacks(noReplyTimeout);
        stopGateQuietly();
        statusText.setText("Отключение…");
        renderButton();
    }

    private void stopGateQuietly() {
        if (service != null && (gateWantsConnection || lastLevel != ConnectionStatus.LEVEL_NOTCONNECTED)) {
            try {
                service.stopVPN(false);
            } catch (RemoteException e) {
                VpnStatus.logException(e);
            }
        }
    }

    @Override
    public void updateState(String state, String logmessage, int localizedResId, ConnectionStatus level, Intent intent) {
        ui.post(() -> onGateState(level, localizedResId));
    }

    private void onGateState(ConnectionStatus level, int resId) {
        lastLevel = level;
        if (isVless()) return;
        switch (level) {
            case LEVEL_CONNECTED:
                ui.removeCallbacks(attemptTimeout);
                ui.removeCallbacks(noReplyTimeout);
                queue.clear();
                gateWantsConnection = true;
                statusText.setText("✅ Подключено");
                break;
            case LEVEL_AUTH_FAILED:
                if (gateWantsConnection) tryNextServer("Сервер отклонил вход");
                else statusText.setText("Ошибка входа");
                break;
            case LEVEL_NOTCONNECTED:
                if (!gateWantsConnection) {
                    statusText.setText("Не подключено");
                    trafficText.setText("");
                }
                break;
            case LEVEL_NONETWORK:
                statusText.setText("Нет сети, жду…");
                break;
            case LEVEL_VPNPAUSED:
                statusText.setText("Пауза");
                break;
            default:
                if (gateWantsConnection) {
                    String detail = "";
                    if (resId != 0) {
                        try {
                            detail = getString(resId);
                        } catch (Exception ignored) {
                        }
                    }
                    statusText.setText("Подключение… " + detail);
                }
        }
        renderButton();
        adapter.notifyDataSetChanged();
    }

    @Override
    public void setConnectedVPN(String uuid) {
    }

    @Override
    public void updateByteCount(long in, long out, long diffIn, long diffOut) {
        ui.post(() -> {
            if (!isVless() && lastLevel == ConnectionStatus.LEVEL_CONNECTED) {
                trafficText.setText("↓ " + human(in) + "   ↑ " + human(out));
            }
        });
    }

    // =================================================================== log (OpenVPN)

    @Override
    public void newLog(LogItem logItem) {
        ui.post(() -> {
            addLogLine(logItem);
            renderLog();
        });
    }

    private void addLogLine(LogItem item) {
        if (item == null) return;
        String text;
        try {
            text = item.getString(this);
        } catch (Exception e) {
            return;
        }
        String time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date(item.getLogtime()));
        logLines.addLast(time + " " + text);
        while (logLines.size() > LOG_LINES) logLines.removeFirst();
    }

    private void renderLog() {
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) sb.append(l).append('\n');
        logView.setText(sb);
        if (logScroll.getVisibility() == View.VISIBLE) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("FreeVPN log", logView.getText()));
            Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_SHORT).show();
        }
    }

    // =================================================================== helpers

    private void renderButton() {
        boolean connected = isVless() ? xrayState == XrayVpnService.State.CONNECTED
                : lastLevel == ConnectionStatus.LEVEL_CONNECTED;
        boolean busy = isVless() ? vlessWantsConnection || xrayState == XrayVpnService.State.CONNECTING
                : gateWantsConnection;
        if (connected) {
            connectButton.setBackgroundResource(R.drawable.freevpn_button_on);
            connectText.setText("ОТКЛЮЧИТЬ");
        } else if (busy) {
            connectButton.setBackgroundResource(R.drawable.freevpn_button_wait);
            connectText.setText("ОТМЕНА");
        } else {
            connectButton.setBackgroundResource(R.drawable.freevpn_button_off);
            connectText.setText("ПОДКЛЮЧИТЬ");
        }
    }

    private void updateServerInfo() {
        if (isVless()) {
            XrayLink k = (vlessWantsConnection || xrayState == XrayVpnService.State.CONNECTED) && currentKey != null
                    ? currentKey : selectedKey;
            serverInfo.setText(k == null ? "Ключ: автовыбор рабочего" : k.name + " · " + k.subtitle());
        } else {
            VpnServer s = gateWantsConnection && currentServer != null ? currentServer : selectedServer;
            serverInfo.setText(s == null ? "Сервер: автовыбор лучшего"
                    : s.flag() + " " + s.countryLong + " · " + s.hostName + " · " + s.speedText());
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f КБ", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f МБ", mb);
        return String.format(Locale.US, "%.2f ГБ", mb / 1024.0);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!prefs.getBoolean("asked_notif", false)) {
                prefs.edit().putBoolean("asked_notif", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            }
        }
    }
}
