package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens the TV's Wi-Fi and Settings screens.
 *
 * Vendor TV firmwares often ship a settings app that only declares its own private actions
 * (Xiaomi Mi TV: com.xiaomi.mitv.settings.WIFI_SETTINGS / .SETTINGS) and ignores the standard
 * android.settings.* intents, which made those buttons do nothing. We therefore probe a list of
 * candidates per screen and use the first one that actually resolves on this device.
 */
final class TvSettings {

    private TvSettings() {}

    static Intent wifi(Context ctx) {
        List<Intent> candidates = new ArrayList<>();
        candidates.add(action("com.xiaomi.mitv.settings.WIFI_SETTINGS"));
        candidates.add(component("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.wifi.MitvWifiActivity"));
        candidates.add(action(Settings.ACTION_WIFI_SETTINGS));
        candidates.add(component("com.android.settings", "com.android.settings.wifi.WifiSettings"));
        candidates.add(component("com.android.settings", "com.android.settings.WifiSettings"));
        return firstResolvable(ctx, candidates);
    }

    static Intent bluetooth(Context ctx) {
        List<Intent> candidates = new ArrayList<>();
        candidates.add(action("com.xiaomi.mitv.settings.BLUETOOTH_SETTINGS"));
        candidates.add(component("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.bluetooth.MitvBluetoothActivity"));
        candidates.add(component("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        candidates.add(action(Settings.ACTION_BLUETOOTH_SETTINGS));
        candidates.add(component("com.android.settings", "com.android.settings.bluetooth.BluetoothSettings"));
        candidates.add(component("com.android.settings", "com.android.settings.BluetoothSettings"));
        return firstResolvable(ctx, candidates);
    }

    static Intent settings(Context ctx) {
        List<Intent> candidates = new ArrayList<>();
        candidates.add(action("com.xiaomi.mitv.settings.SETTINGS"));
        candidates.add(component("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        candidates.add(action("com.xiaomi.mitv.settings.GENERAL_SETTINGS"));
        candidates.add(action(Settings.ACTION_SETTINGS));
        candidates.add(component("com.android.settings", "com.android.settings.Settings"));
        candidates.add(action(Settings.ACTION_SETTINGS).setData(Uri.EMPTY));
        return firstResolvable(ctx, candidates);
    }

    private static Intent action(String action) {
        Intent i = new Intent(action);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static Intent component(String pkg, String cls) {
        Intent i = new Intent();
        i.setClassName(pkg, cls);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static Intent firstResolvable(Context ctx, List<Intent> candidates) {
        PackageManager pm = ctx.getPackageManager();
        for (Intent i : candidates) {
            try {
                if (i.getComponent() != null) {
                    // Explicit component: check it is actually installed and enabled.
                    pm.getActivityInfo(i.getComponent(), 0);
                    return i;
                }
                ResolveInfo ri = pm.resolveActivity(i, 0);
                if (ri != null) return i;
            } catch (Exception ignored) {
                // Not present on this firmware; try the next candidate.
            }
        }
        return null;
    }
}
