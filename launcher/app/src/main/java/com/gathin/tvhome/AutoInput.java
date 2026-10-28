package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Restores the last TV input once, after boot.
 *
 * <p>The stock Mi TV only applies its "default input" setting once, during its own boot sequence;
 * because NLRC is the home screen, the TV ends up on the desktop and the user has to pick an input
 * again after every power cycle. This class fixes that at the level the TV understands:
 *
 * <ul>
 *   <li>On boot ({@link #scheduleBootSwitch}) it waits for the home screen to settle, then picks the
 *       remembered input if it has a signal, otherwise HDMI 3, otherwise the first port with a
 *       signal. It keeps waiting until a 60 s window closes, so a slow set-top box still works.</li>
 *   <li>After that window the watcher stops. It does <b>not</b> poll or steal the screen while the
 *       TV is being used; this is intentionally boot-only.</li>
 *   <li>Every manual selection (Inputs menu, dock tile, hotkey) is remembered, including Live TV,
 *       so the next boot restores the same source.</li>
 * </ul>
 *
 * <p>Detection uses the Xiaomi vendor SDK first:
 * {@code mitv.tv.TvContext.getInstance().getSourceManager().isSourceConnected(sourceId)}. The same
 * SourceManager is what MiTVTVPlayer uses ({@code onSourceSignalStable}, {@code setCurrentSource}),
 * and the Kookong TV SDK bundled in the ROM calls {@code isSourceConnected(int)} for exactly this
 * purpose. It is reached by reflection so the APK still builds and runs on non-Xiaomi TVs.
 *
 * <p>The public {@link TvInputManager} list is the fallback: on this Amlogic ROM an HDMI
 * {@code TvInput} only exists while the port is connected ({@code Hdmi1InputService/HW5} disappears
 * from {@code dumpsys tv_input} when the cable is pulled). The id fragment is configurable
 * ({@code auto_hdmi_tvinput_hint}) and maps to the fallback port only, because TvInputManager does
 * not expose the miTV source id for other ports.
 */
final class AutoInput {

    private static final String TAG = "AutoInput";
    private static final String PREFS = "home";
    private static final String KEY_ENABLED = "auto_input_enabled";
    private static final String KEY_LAST_SOURCE = "last_input_source";
    /** Pending boot switch, in elapsedRealtime ms; survives a process restart inside one boot. */
    private static final String KEY_BOOT_CHECK = "auto_boot_check_at";
    /** Set once the boot switch ran (or was deliberately cancelled) so a process restart won't redo it. */
    private static final String KEY_BOOT_DONE = "auto_boot_done";
    /** Sentinel in last_input_source for Live TV; it is not part of the HDMI/AV signal scan. */
    private static final int SOURCE_LIVE_TV = -2;

    /** Poll cadence inside the boot window; the watcher stops once the window is over. */
    private static final long POLL_MS = 2_000L;
    /** Default grace after the first boot check, so a slow set-top box still gets picked up. */
    private static final long DEFAULT_BOOT_WINDOW_MS = 60_000L;
    /** After a failed boot switch, retry a few times inside the window. */
    private static final long BOOT_RETRY_MS = 5_000L;
    private static final int BOOT_TRIES = 4;
    /** Minimum gap between two automatic launches, so a bounce cannot start the player twice. */
    private static final long SWITCH_COOLDOWN_MS = 4_000L;

    private static AutoInput sInstance;

    private final Context app;
    private final HandlerThread thread;
    private final Handler handler;
    private final int[] sources;
    private final String[] labels;
    /** Port used when there is no remembered source / no other port has a signal (0-based). */
    private final int fallbackIndex;
    private final String inputHint;
    private final boolean miPlayer;

    private volatile boolean running;

    /** Last signal snapshot; also used by the boot fallback when the process is recreated. */
    private boolean[] lastSignal;
    /** Source ids the vendor SourceManager refused; do not log/retry them every poll. */
    private boolean[] sourceFailed;
    /** Port the user (or the TV) last selected, persisted in {@link #KEY_LAST_SOURCE}. */
    private int activeSource = -1;
    private long bootCheckAt = -1;
    /** End of the boot grace window; polling stops when this passes. */
    private long bootWindowEndsAt = -1;
    private int bootTriesLeft;
    private boolean bootCheckLogged;
    private long lastSwitchAt;

    /** Vendor SourceManager, or null when this is not a Xiaomi TV / reflection is blocked. */
    private Object sourceManager;
    private Method isSourceConnected;
    private Method getCurrentSource;
    private boolean vendorReady;
    private boolean vendorTried;
    private long nextVendorRetryAt;
    private Method userUnlockedMethod;

    private TvInputManager tvInputManager;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                tick();
            } catch (Throwable t) {
                Log.w(TAG, "poll failed: " + t);
            }
            // Boot-only design: no permanent 2-second loop while the user is watching TV.
            if (running && bootCheckAt > 0) handler.postDelayed(this, POLL_MS);
        }
    };

    private AutoInput(Context context) {
        app = context.getApplicationContext() != null ? context.getApplicationContext() : context;

        sources = HdmiHelper.autoSources(app);
        labels = HdmiHelper.autoLabels(app);
        int index = readInt(R.integer.auto_hdmi_index, 2);
        fallbackIndex = sources.length == 0 ? -1 : Math.max(0, Math.min(index, sources.length - 1));
        inputHint = readString(R.string.auto_hdmi_tvinput_hint);
        miPlayer = HdmiHelper.isMiPlayer(app);
        activeSource = loadLastSource(app);
        if (activeSource != SOURCE_LIVE_TV && indexOf(activeSource) < 0) {
            activeSource = fallbackIndex >= 0 ? sources[fallbackIndex] : -1;
        }
        lastSignal = new boolean[sources.length];
        sourceFailed = new boolean[sources.length];

        try {
            // API 24+, but the ROM may backport it; reflection keeps minSdk 23 safe.
            userUnlockedMethod = android.os.UserManager.class.getMethod("isUserUnlocked");
        } catch (Throwable ignored) {
        }

        thread = new HandlerThread("nlrc-auto-input");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Starts the watcher once per process; safe to call from any component and any thread. */
    static AutoInput ensureStarted(Context context) {
        AutoInput instance;
        synchronized (AutoInput.class) {
            if (sInstance == null) sInstance = new AutoInput(context);
            instance = sInstance;
        }
        instance.start();
        return instance;
    }

    private synchronized void start() {
        if (running) return;
        running = true;
        handler.post(() -> {
            initVendor();
            nextVendorRetryAt = SystemClock.elapsedRealtime() + 30_000L;
            initTvInput();
            // Seed the edge detector with the current state: starting the app (or the accessibility
            // service reconnecting) while an input is already showing must not yank the user back.
            lastSignal = readSignals();
            if (activeSource < 0 && fallbackIndex >= 0) activeSource = sources[fallbackIndex];
            Log.i(TAG, "started: active=" + labelOf(activeSource) + ", signals=["
                    + describe(lastSignal) + "], vendor=" + vendorReady);
            // The launcher process can die and restart between BOOT_COMPLETED and the delayed check
            // (this TV did exactly that in updateWifiLabel). Pick the pending check back up.
            resumeBootCheck();
            if (bootCheckAt > 0) handler.postDelayed(poll, POLL_MS);
        });
    }

    /**
     * Schedules the one-shot boot switch. Called after BOOT_COMPLETED has launched the home screen,
     * so the input wins the foreground instead of being covered by the launcher.
     */
    static void scheduleBootSwitch(Context context) {
        final AutoInput instance = ensureStarted(context);
        if (!instance.running || instance.sources.length == 0 || !instance.miPlayer) return;
        final long delay = instance.readInt(R.integer.auto_hdmi_boot_delay_ms, 10_000);
        final long window = instance.bootWindowMs();
        final long at = SystemClock.elapsedRealtime() + Math.max(0L, delay);
        instance.handler.post(() -> {
            instance.bootCheckAt = at;
            instance.bootWindowEndsAt = at + window;
            instance.bootTriesLeft = BOOT_TRIES;
            instance.bootCheckLogged = false;
            instance.saveBootCheck(at);
            Log.i(TAG, "boot switch scheduled in " + delay + " ms (window " + window + " ms)");
            instance.handler.removeCallbacks(instance.poll);
            instance.handler.post(instance.poll);
        });
    }

    private long bootWindowMs() {
        return Math.max(10_000L, readInt(R.integer.auto_hdmi_boot_window_ms, (int) DEFAULT_BOOT_WINDOW_MS));
    }

    /** Remembers an HDMI/AV source selected from the Inputs menu, dock tile or a hotkey. */
    static void noteSelectedSource(Context context, int source) {
        if (source < 0 || !containsSource(context, source)) return;
        saveLastSource(context, source);
        AutoInput instance = sInstance;
        if (instance != null) {
            instance.handler.post(() -> {
                instance.activeSource = source;
                instance.dropBootCheck();   // a deliberate input choice ends any pending boot switch
            });
        }
    }

    /** Remembers Live TV; boot restores it via AtvActivity instead of a signal scan. */
    static void noteSelectedLiveTv(Context context) {
        saveLastSource(context, SOURCE_LIVE_TV);
        AutoInput instance = sInstance;
        if (instance != null) {
            instance.handler.post(() -> {
                instance.activeSource = SOURCE_LIVE_TV;
                instance.dropBootCheck();
            });
        }
    }

    static boolean isEnabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;   // direct boot / storage not ready: keep the feature on
        }
    }

    static void setEnabled(Context context, boolean enabled) {
        try {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        } catch (Throwable t) {
            Log.w(TAG, "could not save auto input preference: " + t);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void saveLastSource(Context context, int source) {
        try {
            prefs(context).edit().putInt(KEY_LAST_SOURCE, source).apply();
        } catch (Throwable t) {
            Log.w(TAG, "could not save last source: " + t);
        }
    }

    private static int loadLastSource(Context context) {
        try {
            return prefs(context).getInt(KEY_LAST_SOURCE, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean containsSource(Context context, int source) {
        try {
            for (int s : HdmiHelper.autoSources(context)) {
                if (s == source) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Persists a pending boot switch so a process restart inside the same boot can resume it. */
    private void saveBootCheck(long atElapsed) {
        try {
            prefs(app).edit().putLong(KEY_BOOT_CHECK, atElapsed).putBoolean(KEY_BOOT_DONE, false).apply();
        } catch (Throwable t) {
            Log.w(TAG, "could not persist boot check: " + t);
        }
    }

    /** Forgets a pending check without marking this boot as handled (stale/too-late entry). */
    private void clearBootCheckOnly() {
        try {
            prefs(app).edit().remove(KEY_BOOT_CHECK).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Ends the boot check (switched, no signal, disabled or user interacted) and marks it handled. */
    private void dropBootCheck() {
        bootCheckAt = -1;
        try {
            prefs(app).edit().remove(KEY_BOOT_CHECK).putBoolean(KEY_BOOT_DONE, true).apply();
        } catch (Throwable t) {
            Log.w(TAG, "could not clear boot check: " + t);
        }
    }

    private long loadBootCheck() {
        try {
            return prefs(app).getLong(KEY_BOOT_CHECK, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private boolean isBootDone() {
        try {
            return prefs(app).getBoolean(KEY_BOOT_DONE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Re-arms a boot switch that BootReceiver scheduled before this process existed. The value is
     * elapsedRealtime, so it survives a process restart but not a reboot; a stale value from the
     * previous boot is discarded. If no value exists and the app starts within two minutes of
     * boot with a signal, a short fallback is scheduled.
     */
    private void resumeBootCheck() {
        long now = SystemClock.elapsedRealtime();
        long window = bootWindowMs();
        long at = loadBootCheck();
        if (at > 0) {
            long remaining = at - now;
            if (remaining > 30_000L) {
                clearBootCheckOnly();          // previous boot's elapsedRealtime
            } else if (now <= at + window) {
                // Late process restart is fine as long as the boot window is still open.
                bootCheckAt = now + Math.max(0L, remaining);
                bootWindowEndsAt = at + window;
                bootTriesLeft = BOOT_TRIES;
                bootCheckLogged = false;
                Log.i(TAG, "resumed boot switch, " + remaining + " ms left, window ends at +"
                        + (bootWindowEndsAt - now) + " ms");
                return;
            } else {
                clearBootCheckOnly();          // boot window already closed
            }
        }
        if (isBootDone()) return;
        if ((activeSource == SOURCE_LIVE_TV || anySignal(lastSignal)) && now < 120_000L) {
            bootCheckAt = now + 8_000L;
            bootWindowEndsAt = now + window;
            bootTriesLeft = BOOT_TRIES;
            bootCheckLogged = false;
            Log.i(TAG, "recent boot + last source: fallback boot switch in 8 s");
        }
    }

    private void tick() {
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;   // no point switching while the panel is off

        long now = SystemClock.elapsedRealtime();
        // A direct-boot process can see the vendor library appear only after unlock, so retry
        // quietly once in a while instead of giving up on the first ClassNotFoundException.
        if (!vendorReady && now >= nextVendorRetryAt) {
            initVendor();
            nextVendorRetryAt = now + 30_000L;
        }

        boolean[] signal = readSignals();
        updateActiveFromVendor();

        if (bootCheckAt > 0 && now >= bootCheckAt) {
            if (!bootCheckLogged) {
                bootCheckLogged = true;
                Log.i(TAG, "boot check: active=" + labelOf(activeSource) + ", signals=["
                        + describe(signal) + "], enabled=" + isEnabled(app));
            }
            if (activeSource == SOURCE_LIVE_TV) {
                // Live TV is a tuner: it has no plug-in signal, but restoring the last source is
                // exactly what "开机进入" means. Runtime switching is still off.
                if (canSwitch(now)) {
                    if (switchToLiveTv("boot")) dropBootCheck();
                    else retryBootOrDrop(now);
                } else if (!isEnabled(app)) {
                    dropBootCheck();
                }
            } else {
                int pick = pickBootSource(signal);
                if (pick >= 0) {
                    if (canSwitch(now)) {
                        if (switchTo(pick, "boot")) dropBootCheck();
                        else retryBootOrDrop(now);
                    } else if (!isEnabled(app)) {
                        dropBootCheck();   // the feature is off
                    }
                    // Otherwise a short cooldown is still running; the next poll retries.
                } else if (now >= bootWindowEndsAt) {
                    Log.i(TAG, "boot window expired without a switchable signal");
                    dropBootCheck();
                }
            }
        }

        lastSignal = signal;
    }

    /** Boot priority: remembered source, then HDMI 3 / configured fallback, then config order. */
    private int pickBootSource(boolean[] signal) {
        int activeIndex = indexOf(activeSource);
        if (activeIndex >= 0 && signal[activeIndex]) return activeIndex;
        if (fallbackIndex >= 0 && signal[fallbackIndex]) return fallbackIndex;
        for (int i = 0; i < sources.length; i++) {
            if (signal[i]) return i;
        }
        return -1;
    }

    private boolean canSwitch(long now) {
        if (!miPlayer || sources.length == 0) return false;
        if (!isUserUnlocked()) return false;   // never launch an activity from direct boot
        if (!isEnabled(app)) return false;
        return now - lastSwitchAt >= SWITCH_COOLDOWN_MS;
    }

    private boolean isUserUnlocked() {
        if (userUnlockedMethod == null) return true;
        try {
            Object um = app.getSystemService(Context.USER_SERVICE);
            Object value = um == null ? null : userUnlockedMethod.invoke(um);
            return !(value instanceof Boolean) || (Boolean) value;
        } catch (Throwable t) {
            return true;   // do not lose the feature over a framework quirk
        }
    }

    private boolean switchTo(int index, String reason) {
        if (index < 0 || index >= sources.length) return false;
        int source = sources[index];
        try {
            Intent intent = HdmiHelper.createMiSourceIntent(app, source);
            app.startActivity(intent);
            lastSwitchAt = SystemClock.elapsedRealtime();
            activeSource = source;
            saveLastSource(app, source);
            Log.i(TAG, "switched to " + label(index) + " (source " + source + ") - " + reason);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "could not switch to " + label(index) + ": " + t);
            return false;
        }
    }

    private boolean switchToLiveTv(String reason) {
        try {
            Intent intent = HdmiHelper.createTvIntent(app);
            app.startActivity(intent);
            lastSwitchAt = SystemClock.elapsedRealtime();
            activeSource = SOURCE_LIVE_TV;
            saveLastSource(app, SOURCE_LIVE_TV);
            Log.i(TAG, "switched to Live TV - " + reason);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "could not switch to Live TV: " + t);
            return false;
        }
    }

    private void retryBootOrDrop(long now) {
        bootTriesLeft--;
        if (bootTriesLeft > 0) {
            bootCheckAt = now + BOOT_RETRY_MS;
            saveBootCheck(bootCheckAt);
        } else {
            dropBootCheck();
        }
    }

    /** Reads isSourceConnected for every configured port. */
    private boolean[] readSignals() {
        boolean[] signal = new boolean[sources.length];
        for (int i = 0; i < sources.length; i++) {
            signal[i] = readSignalFor(i);
        }
        return signal;
    }

    private boolean readSignalFor(int index) {
        int source = sources[index];
        if (vendorReady && !sourceFailed[index]) {
            Boolean connected = invokeIsConnected(source);
            if (connected != null) return connected;
            // One unsupported id (AV is the usual suspect) must not disable the other ports.
            sourceFailed[index] = true;
            Log.i(TAG, "SourceManager has no state for source " + source + " (" + label(index)
                    + "); skipping that source");
        }
        // TvInputManager cannot map HW ids to miTV source ids; only the configured fallback port
        // can be recognized safely. Other ports simply report false unless the vendor SDK works.
        return index == fallbackIndex && hasHdmiInput();
    }

    private Boolean invokeIsConnected(int source) {
        if (sourceManager == null || isSourceConnected == null) return null;
        try {
            Object value = isSourceConnected.invoke(sourceManager, source);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable t) {
            Log.w(TAG, "SourceManager.isSourceConnected(" + source + ") failed: " + t);
            return null;
        }
    }

    /** Mirrors the TV's own current source into the remembered one (covers stock settings). */
    private void updateActiveFromVendor() {
        if (!vendorReady || getCurrentSource == null) return;
        try {
            Object value = getCurrentSource.invoke(sourceManager);
            if (!(value instanceof Integer)) return;
            int current = (Integer) value;
            if (current == activeSource || indexOf(current) < 0) return;
            activeSource = current;
            saveLastSource(app, current);
            Log.i(TAG, "current source -> " + labelOf(current));
        } catch (Throwable t) {
            Log.w(TAG, "SourceManager.getCurrentSource failed, not mirroring: " + t);
            getCurrentSource = null;   // stop retrying every poll
        }
    }

    private boolean hasHdmiInput() {
        List<TvInputInfo> inputs = hdmiInputs();
        if (inputs.isEmpty()) return false;
        if (inputHint.isEmpty()) return true;
        for (TvInputInfo info : inputs) {
            String id = info.getId();
            if (id != null && id.contains(inputHint)) return true;
        }
        return false;
    }

    private List<TvInputInfo> hdmiInputs() {
        if (tvInputManager == null) return java.util.Collections.emptyList();
        try {
            List<TvInputInfo> all = tvInputManager.getTvInputList();
            java.util.ArrayList<TvInputInfo> hdmi = new java.util.ArrayList<>();
            if (all != null) {
                for (TvInputInfo info : all) {
                    if (info != null && info.getType() == TvInputInfo.TYPE_HDMI && info.isPassthroughInput()) {
                        hdmi.add(info);
                    }
                }
            }
            return hdmi;
        } catch (Throwable t) {
            Log.w(TAG, "TvInputManager list failed: " + t);
            return java.util.Collections.emptyList();
        }
    }

    private void initVendor() {
        try {
            Class<?> tvContext = Class.forName("mitv.tv.TvContext");
            Object context = tvContext.getMethod("getInstance").invoke(null);
            if (context == null) throw new IllegalStateException("TvContext.getInstance() == null");
            Object manager = tvContext.getMethod("getSourceManager").invoke(context);
            if (manager == null) throw new IllegalStateException("getSourceManager() == null");
            Class<?> sourceManagerClass = Class.forName("mitv.tv.SourceManager");
            Method connected = sourceManagerClass.getMethod("isSourceConnected", int.class);
            sourceManager = manager;
            isSourceConnected = connected;
            try {
                getCurrentSource = sourceManagerClass.getMethod("getCurrentSource");
            } catch (Throwable ignored) {
                getCurrentSource = null;
            }
            vendorReady = true;
            Log.i(TAG, "vendor SourceManager ready");
        } catch (Throwable t) {
            vendorReady = false;
            if (!vendorTried) Log.i(TAG, "vendor SourceManager unavailable, using TvInputManager: " + t);
        } finally {
            vendorTried = true;
        }
    }

    private void initTvInput() {
        try {
            tvInputManager = (TvInputManager) app.getSystemService(Context.TV_INPUT_SERVICE);
            if (tvInputManager != null) {
                StringBuilder ids = new StringBuilder();
                for (TvInputInfo info : tvInputManager.getTvInputList()) {
                    if (info != null && info.getType() == TvInputInfo.TYPE_HDMI) {
                        if (ids.length() > 0) ids.append(", ");
                        ids.append(info.getId());
                    }
                }
                Log.i(TAG, "HDMI TvInputs: [" + ids + "]");
            }
        } catch (Throwable t) {
            Log.w(TAG, "no TvInputManager: " + t);
        }
    }

    private int indexOf(int source) {
        for (int i = 0; i < sources.length; i++) {
            if (sources[i] == source) return i;
        }
        return -1;
    }

    private String label(int index) {
        return index >= 0 && index < labels.length ? labels[index] : "HDMI";
    }

    private String labelOf(int source) {
        int index = indexOf(source);
        return index >= 0 ? label(index) : String.valueOf(source);
    }

    private String describe(boolean[] signal) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.length; i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(label(i)).append('=').append(signal[i] ? "on" : "off");
        }
        return sb.toString();
    }

    private static boolean anySignal(boolean[] signal) {
        for (boolean b : signal) {
            if (b) return true;
        }
        return false;
    }

    private int readInt(int resId, int fallback) {
        try {
            return app.getResources().getInteger(resId);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private String readString(int resId) {
        try {
            String value = app.getString(resId);
            return value == null ? "" : value.trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
