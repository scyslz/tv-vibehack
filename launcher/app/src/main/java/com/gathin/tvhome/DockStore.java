package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Persistent, ordered list of the tiles pinned to the dock.
 * New pins are appended, so the dock keeps the order in which the user added them.
 * Every access re-reads the preferences, so several instances stay in sync.
 */
final class DockStore {

    private static final String PREFS = "home";
    private static final String KEY   = "dock_keys";

    private final Context ctx;

    DockStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        SharedPreferences sp = prefs();
        if (!sp.contains(KEY)) {
            // First run: seed with the tiles that used to be pinned by config.
            List<String> seed = Arrays.asList(
                    ctx.getResources().getStringArray(R.array.dock_defaults));
            sp.edit().putString(KEY, join(seed)).apply();
        }
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<String> keys() {
        String raw = prefs().getString(KEY, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String k : raw.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    boolean contains(String key) {
        return keys().contains(key);
    }

    int size() {
        return keys().size();
    }

    /** Appends the tile at the end of the dock. Returns false when it is already pinned. */
    boolean add(String key) {
        if (key == null || key.isEmpty()) return false;
        List<String> cur = keys();
        if (cur.contains(key)) return false;
        cur.add(key);
        save(cur);
        return true;
    }

    boolean remove(String key) {
        List<String> cur = keys();
        if (!cur.remove(key)) return false;
        save(cur);
        return true;
    }

    private void save(List<String> keys) {
        prefs().edit().putString(KEY, join(keys)).apply();
    }

    private static String join(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(k);
        }
        return sb.toString();
    }
}
