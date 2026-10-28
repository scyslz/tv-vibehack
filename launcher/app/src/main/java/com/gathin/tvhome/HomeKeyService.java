package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.res.TypedArray;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Intercepts the Home key and the stock launcher coming to the foreground, and brings this
 * launcher to the front. The stock launcher is NEVER disabled, so if this app crashes or is
 * uninstalled the TV simply falls back to its factory home screen.
 *
 * The list of stock launcher packages is configurable (res/values/config.xml, stock_launchers)
 * so the same APK works on Google TV, Xiaomi PatchWall and other vendor skins.
 */
public class HomeKeyService extends AccessibilityService {

    private static final String TAG = "HomeKeyService";
    private static final String NETFLIX_PKG = "com.netflix.ninja";
    private static final String PRIME_PKG = "com.amazon.amazonvideo.livingroom";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> stockLaunchers = new HashSet<>();
    private long lastLaunchTime = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // The accessibility service is the most reliable long-lived host for the input watcher.
        AutoInput.ensureStarted(this);
        try {
            for (String p : getResources().getStringArray(R.array.stock_launchers)) {
                if (!p.trim().isEmpty()) stockLaunchers.add(p.trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "No stock_launchers list: " + e.getMessage());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgName = event.getPackageName();
            if (pkgName == null) return;
            String pkg = pkgName.toString();
            if (stockLaunchers.contains(pkg)) {
                // The factory home screen is being shown; replace it with this launcher.
                launchHome();
            } else if (NETFLIX_PKG.equals(pkg) || PRIME_PKG.equals(pkg)) {
                // Dedicated remote hotkeys land here; send them to the set-top box input instead.
                Log.i(TAG, "Intercepted " + pkg + " -> switching to HDMI 2");
                HdmiHelper.switchToHdmi2(this, true);
            }
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();

            if (keyCode == KeyEvent.KEYCODE_HOME) {
                launchHome();
                return true; // Consume the event so the stock launcher is not invoked
            }

            // Sponsored remote buttons (Netflix / Prime / colour keys) -> HDMI 2 set-top box.
            if (keyCode == KeyEvent.KEYCODE_BUTTON_3      // Netflix button
                    || keyCode == KeyEvent.KEYCODE_BUTTON_4  // Prime Video button
                    || keyCode == KeyEvent.KEYCODE_PROG_RED  // Colour Red
                    || keyCode == KeyEvent.KEYCODE_PROG_GREEN) { // Colour Green
                Log.i(TAG, "Intercepted remote key " + keyCode + " -> HDMI 2");
                HdmiHelper.switchToHdmi2(this, true);
                return true;
            }
        }
        return super.onKeyEvent(event);
    }

    private synchronized void launchHome() {
        long now = System.currentTimeMillis();
        if (now - lastLaunchTime < 400) return; // debounce duplicate launches
        lastLaunchTime = now;

        handler.post(() -> {
            try {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch home", e);
            }
        });
    }

    @Override
    public void onInterrupt() {
        // Required callback for AccessibilityService
    }
}
