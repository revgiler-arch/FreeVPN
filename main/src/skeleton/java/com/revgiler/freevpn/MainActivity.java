package com.revgiler.freevpn;

import android.Manifest;
import android.app.Activity;
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

/**
 * FreeVPN main screen: list of free VPN Gate servers, one big connect button,
 * automatic switch to the next server when a free server does not answer.
 */
public class MainActivity extends Activity implements VpnStatus.StateListener, VpnStatus.ByteCountListener,
        VpnStatus.LogListener {

    private static final String PROFILE_PREFIX = "FreeVPN: ";
    private static final int REQ_VPN_PERMISSION = 70;
    private static final int REQ_NOTIFICATIONS = 71;
    /** How long a free server gets to connect before we try the next one. */
    private static final long ATTEMPT_TIMEOUT_MS = 20_000;
    private static final int MAX_ATTEMPTS = 8;
    /** If the server has not answered at all after this long, it is probably unreachable. */
    private static final long NO_REPLY_TIMEOUT_MS = 10_000;
    private static final int LOG_LINES = 120;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private View connectButton;
    private TextView connectText, statusText, serverInfo, trafficText, listHint;
    private Spinner countrySpinner;
    private ListView serverList;
    private TextView logView, logToggle;
    private ScrollView logScroll;
    private final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();

    private final List<VpnServer> allServers = new ArrayList<>();
    private final List<VpnServer> shownServers = new ArrayList<>();
    private ServerAdapter adapter;
    private String selectedCountry = null; // null = all countries
    private VpnServer selectedServer = null;

    /** Servers still to try in the current connect attempt. */
    private final List<VpnServer> queue = new ArrayList<>();
    private VpnServer currentServer = null;
    private int attempts = 0;
    private boolean userWantsConnection = false;
    private ConnectionStatus lastLevel = ConnectionStatus.LEVEL_NOTCONNECTED;

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
        if (userWantsConnection && lastLevel == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET) {
            tryNextServer("Сервер недоступен");
        }
    };

    private final Runnable attemptTimeout = () -> {
        if (userWantsConnection && lastLevel != ConnectionStatus.LEVEL_CONNECTED) {
            tryNextServer("Сервер не ответил");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.freevpn_main);

        connectButton = findViewById(R.id.fv_connect);
        connectText = findViewById(R.id.fv_connect_text);
        statusText = findViewById(R.id.fv_status);
        serverInfo = findViewById(R.id.fv_server_info);
        trafficText = findViewById(R.id.fv_traffic);
        listHint = findViewById(R.id.fv_list_hint);
        countrySpinner = findViewById(R.id.fv_country);
        serverList = findViewById(R.id.fv_servers);
        logView = findViewById(R.id.fv_log);
        logToggle = findViewById(R.id.fv_log_toggle);
        logScroll = findViewById(R.id.fv_log_scroll);
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
        logView.setOnLongClickListener(v -> {
            copyLog();
            return true;
        });

        adapter = new ServerAdapter();
        serverList.setAdapter(adapter);
        serverList.setOnItemClickListener((parent, view, position, id) -> {
            VpnServer s = shownServers.get(position);
            if (selectedServer != null && selectedServer.id().equals(s.id())) {
                selectedServer = null; // tap again = back to automatic choice
                serverList.clearChoices();
            } else {
                selectedServer = s;
            }
            adapter.notifyDataSetChanged();
            updateServerInfo();
        });

        connectButton.setOnClickListener(v -> onConnectClicked());
        findViewById(R.id.fv_refresh).setOnClickListener(v -> loadServers());

        askNotificationPermission();
        loadServers();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, OpenVPNService.class);
        intent.setAction(OpenVPNService.START_SERVICE);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        VpnStatus.addStateListener(this);
        VpnStatus.addByteCountListener(this);
        logLines.clear();
        LogItem[] buffer = VpnStatus.getlogbuffer();
        for (int i = Math.max(0, buffer.length - LOG_LINES); i < buffer.length; i++) addLogLine(buffer[i]);
        renderLog();
        VpnStatus.addLogListener(this);
    }

    @Override
    protected void onStop() {
        VpnStatus.removeStateListener(this);
        VpnStatus.removeByteCountListener(this);
        VpnStatus.removeLogListener(this);
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
        super.onDestroy();
    }

    // ---------------------------------------------------------------- server list

    private void loadServers() {
        listHint.setText("Загрузка серверов…");
        io.execute(() -> {
            try {
                VpnGateApi.Result r = VpnGateApi.load(getApplicationContext(), (n, total) ->
                        ui.post(() -> listHint.setText("Загрузка серверов… (источник " + n + " из " + total + ")")));
                ui.post(() -> {
                    allServers.clear();
                    allServers.addAll(r.servers);
                    setupCountries();
                    applyFilter();
                    listHint.setText((r.fromCache ? "Нет связи с VPN Gate — показан прошлый список. " : "")
                            + "Серверов: " + shownServers.size()
                            + ". Нажми на сервер, чтобы выбрать, или просто «Подключить» — выберу лучший.");
                });
            } catch (Throwable e) {
                ui.post(() -> listHint.setText("Не удалось загрузить список: " + e.getMessage()
                        + "\nПроверь интернет и нажми «Обновить»."));
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

    private class ServerAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shownServers.size();
        }

        @Override
        public Object getItem(int position) {
            return shownServers.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.freevpn_server_row, parent, false);
            VpnServer s = shownServers.get(position);
            ((TextView) v.findViewById(R.id.fv_row_flag)).setText(s.flag());
            ((TextView) v.findViewById(R.id.fv_row_title)).setText(s.countryLong + " · " + s.hostName);
            ((TextView) v.findViewById(R.id.fv_row_sub)).setText(s.speedText() + " · сессий: " + s.sessions);
            ((TextView) v.findViewById(R.id.fv_row_ping)).setText(s.pingText());
            boolean selected = selectedServer != null && selectedServer.id().equals(s.id());
            boolean active = currentServer != null && currentServer.id().equals(s.id())
                    && lastLevel == ConnectionStatus.LEVEL_CONNECTED;
            v.setActivated(selected || active);
            return v;
        }
    }

    // ---------------------------------------------------------------- connecting

    private void onConnectClicked() {
        if (userWantsConnection || lastLevel == ConnectionStatus.LEVEL_CONNECTED) {
            disconnect();
            return;
        }
        if (allServers.isEmpty()) {
            Toast.makeText(this, "Список серверов ещё не загружен", Toast.LENGTH_SHORT).show();
            loadServers();
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
            startConnecting();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) startConnecting();
            else Toast.makeText(this, "Без разрешения VPN подключиться нельзя", Toast.LENGTH_LONG).show();
        }
    }

    private void startConnecting() {
        queue.clear();
        if (selectedServer != null) queue.add(selectedServer);
        for (VpnServer s : shownServers) { // already sorted, best first
            if (queue.size() >= MAX_ATTEMPTS) break;
            if (!queue.contains(s)) queue.add(s);
        }
        attempts = 0;
        userWantsConnection = true;
        tryNextServer(null);
    }

    private void tryNextServer(String reasonForSkip) {
        ui.removeCallbacks(attemptTimeout);
        ui.removeCallbacks(noReplyTimeout);
        if (!userWantsConnection) return;
        if (queue.isEmpty()) {
            userWantsConnection = false;
            stopVpnQuietly();
            statusText.setText("Не удалось подключиться");
            serverInfo.setText("Попробуй другую страну или нажми «Обновить»");
            renderButton(ConnectionStatus.LEVEL_NOTCONNECTED);
            return;
        }
        if (reasonForSkip != null && currentServer != null) {
            Toast.makeText(this, reasonForSkip + " (" + currentServer.countryLong + "), пробую следующий…",
                    Toast.LENGTH_SHORT).show();
        }
        currentServer = queue.remove(0);
        attempts++;
        try {
            launch(currentServer);
            statusText.setText("Подключение… (попытка " + attempts + ")");
            updateServerInfo();
            renderButton(ConnectionStatus.LEVEL_START);
            ui.postDelayed(attemptTimeout, ATTEMPT_TIMEOUT_MS);
            ui.postDelayed(noReplyTimeout, NO_REPLY_TIMEOUT_MS);
        } catch (Exception e) {
            tryNextServer("Ошибка конфигурации");
        }
    }

    private void launch(VpnServer s) throws Exception {
        ConfigParser parser = new ConfigParser();
        parser.parseConfig(new StringReader(VpnGateApi.config(s)));
        VpnProfile profile = parser.convertProfile();
        profile.mName = PROFILE_PREFIX + s.countryLong + " " + s.hostName;

        ProfileManager pm = ProfileManager.getInstance(this);
        // Keep only one FreeVPN profile so old servers don't pile up.
        for (VpnProfile old : new ArrayList<>(pm.getProfiles())) {
            if (old.mName != null && old.mName.startsWith(PROFILE_PREFIX)) pm.removeProfile(this, old);
        }
        pm.addProfile(profile);
        ProfileManager.saveProfile(this, profile);
        pm.saveProfileList(this);

        VPNLaunchHelper.startOpenVpn(profile, getBaseContext(), "FreeVPN", true);
    }

    private void disconnect() {
        userWantsConnection = false;
        queue.clear();
        ui.removeCallbacks(attemptTimeout);
        ui.removeCallbacks(noReplyTimeout);
        stopVpnQuietly();
        statusText.setText("Отключение…");
    }

    private void stopVpnQuietly() {
        if (service != null) {
            try {
                service.stopVPN(false);
            } catch (RemoteException e) {
                VpnStatus.logException(e);
            }
        }
    }

    // ---------------------------------------------------------------- status from the VPN core

    @Override
    public void updateState(String state, String logmessage, int localizedResId, ConnectionStatus level, Intent intent) {
        ui.post(() -> onState(level, localizedResId));
    }

    private void onState(ConnectionStatus level, int resId) {
        lastLevel = level;
        switch (level) {
            case LEVEL_CONNECTED:
                ui.removeCallbacks(attemptTimeout);
                queue.clear();
                userWantsConnection = true;
                statusText.setText("✅ Подключено");
                break;
            case LEVEL_AUTH_FAILED:
                if (userWantsConnection) tryNextServer("Сервер отклонил вход");
                else statusText.setText("Ошибка входа");
                break;
            case LEVEL_NOTCONNECTED:
                if (!userWantsConnection) {
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
                if (userWantsConnection) {
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
        renderButton(level);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void setConnectedVPN(String uuid) {
    }

    // ---------------------------------------------------------------- log

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

    @Override
    public void updateByteCount(long in, long out, long diffIn, long diffOut) {
        ui.post(() -> {
            if (lastLevel == ConnectionStatus.LEVEL_CONNECTED) {
                trafficText.setText("↓ " + human(in) + "   ↑ " + human(out));
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private void renderButton(ConnectionStatus level) {
        if (level == ConnectionStatus.LEVEL_CONNECTED) {
            connectButton.setBackgroundResource(R.drawable.freevpn_button_on);
            connectText.setText("ОТКЛЮЧИТЬ");
        } else if (userWantsConnection) {
            connectButton.setBackgroundResource(R.drawable.freevpn_button_wait);
            connectText.setText("ОТМЕНА");
        } else {
            connectButton.setBackgroundResource(R.drawable.freevpn_button_off);
            connectText.setText("ПОДКЛЮЧИТЬ");
        }
    }

    private void updateServerInfo() {
        VpnServer s = userWantsConnection && currentServer != null ? currentServer : selectedServer;
        if (s == null) {
            serverInfo.setText("Сервер: автовыбор лучшего");
        } else {
            serverInfo.setText(s.flag() + " " + s.countryLong + " · " + s.hostName + " · " + s.speedText());
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
            SharedPreferences p = getPreferences(MODE_PRIVATE);
            if (!p.getBoolean("asked_notif", false)) {
                p.edit().putBoolean("asked_notif", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            }
        }
    }
}
