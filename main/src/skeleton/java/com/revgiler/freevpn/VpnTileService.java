package com.revgiler.freevpn;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick settings tile: turns the VLESS VPN on/off from the notification shade,
 * reconnecting with the keys that worked last time.
 */
public class VpnTileService extends TileService implements XrayVpnService.Listener {

    @Override
    public void onStartListening() {
        super.onStartListening();
        XrayVpnService.addListener(this);
    }

    @Override
    public void onStopListening() {
        XrayVpnService.removeListener(this);
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();
        XrayVpnService.State s = XrayVpnService.getState();
        if (s == XrayVpnService.State.CONNECTED || s == XrayVpnService.State.CONNECTING) {
            XrayVpnService.stop(this);
            return;
        }
        // No VPN permission yet or no key used before: open the app.
        if (VpnService.prepare(this) != null || !XrayVpnService.hasLast(this)) {
            openApp();
            return;
        }
        if (!XrayVpnService.startLast(this)) openApp();
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }

    @Override
    public void onXrayState(XrayVpnService.State state, String message) {
        Tile t = getQsTile();
        if (t == null) return;
        switch (state) {
            case CONNECTED:
                t.setState(Tile.STATE_ACTIVE);
                if (Build.VERSION.SDK_INT >= 29) t.setSubtitle("Подключено");
                break;
            case CONNECTING:
                t.setState(Tile.STATE_ACTIVE);
                if (Build.VERSION.SDK_INT >= 29) t.setSubtitle("Подключение…");
                break;
            default:
                t.setState(Tile.STATE_INACTIVE);
                if (Build.VERSION.SDK_INT >= 29) t.setSubtitle("Выключено");
        }
        t.updateTile();
    }

    @Override
    public void onXrayTraffic(long down, long up) {
    }
}
