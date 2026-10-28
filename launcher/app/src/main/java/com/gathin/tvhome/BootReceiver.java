package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Ensures NLRC launches automatically on boot and wake from deep sleep.
 * Marked android:directBootAware="true" so it can trigger as early as possible.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Received boot/power broadcast: " + action);

        ensureAccessibilityEnabled(context);
        // Start the input restorer early; the actual boot check is scheduled below.
        AutoInput.ensureStarted(context);

        // LOCKED_BOOT_COMPLETED / QUICKBOOT_POWERON can arrive while user 0 is still locked. Starting
        // the home screen then paints it once from the direct-boot process, and the system tears that
        // process down the moment the user is unlocked: the launcher is then created a second time
        // and visibly re-lays itself out ("the desktop reloads once after boot"). Wait for the
        // unlocked boot instead - BOOT_COMPLETED / USER_UNLOCKED are only sent after unlock.
        if (!isUserUnlocked(context)) {
            Log.i(TAG, "User 0 still locked - leaving the launch to BOOT_COMPLETED / USER_UNLOCKED");
            return;
        }

        launchHome(context);
        // After the desktop is up, enter the last input (or HDMI 3) if it has a signal. The delay
        // keeps the boot from being stolen by the input activity.
        AutoInput.scheduleBootSwitch(context);
    }

    /** False while the device is in direct boot (credential-encrypted storage still locked). */
    private static boolean isUserUnlocked(Context context) {
        // UserManager.isUserUnlocked() only exists on API 24+. Calling it directly on this API 23
        // Mi TV throws NoSuchMethodError (an Error, not an Exception), crashing BOOT_COMPLETED
        // before the home screen or the HDMI auto-switch could run.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        try {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return um == null || um.isUserUnlocked();
        } catch (Exception e) {
            Log.w(TAG, "isUserUnlocked failed: " + e.getMessage());
            return true;   // do not lose the autostart over a permission blip
        }
    }

    /** Brings the launcher to the front without creating a second instance (launchMode singleTask). */
    static void launchHome(Context context) {
        try {
            Intent home = new Intent(context, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(home);
        } catch (Exception e) {
            Log.e(TAG, "Failed to autostart NLRC on boot", e);
        }
    }

    public static void ensureAccessibilityEnabled(Context context) {
        try {
            String serviceName = context.getPackageName() + "/" + HomeKeyService.class.getName();
            String enabledServices = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null || !enabledServices.contains(serviceName)) {
                String newServices = (enabledServices == null || enabledServices.isEmpty()) ? serviceName : enabledServices + ":" + serviceName;
                Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices);
            }
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception e) {
            Log.w(TAG, "Could not self-heal accessibility service: " + e.getMessage());
        }
    }
}
