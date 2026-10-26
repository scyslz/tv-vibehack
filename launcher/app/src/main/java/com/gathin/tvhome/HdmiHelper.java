package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * Switches the TV to an external source (HDMI / AV / tuner).
 *
 * Two hardware families are supported:
 *  - Xiaomi Mi TV / Redmi TV (Amlogic): MiTVTVPlayer's ExternalSourceActivity takes the
 *    hardware source id as an "input" int extra. The ids are per firmware and live in
 *    res/values/config.xml (source_hdmi + hdmi_labels); nothing is hard-coded here.
 *    Measured on a Mi TV 4A (xmen / Amlogic): the framework registers a single HDMI
 *    passthrough input (com.droidlogic.tvinput/.services.Hdmi1InputService/HW5) and
 *    MiTVTVPlayer calls it source id 25 - the same id the stock settings use. The
 *    previously assumed 2 / 18 / 19 do not exist on this ROM.
 *  - Android TV / Google TV (Realtek etc.): standard TvContract passthrough channel URI.
 */
public final class HdmiHelper {

    private static final String TAG = "HdmiHelper";
    private static final String MI_PLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String MI_SOURCE_ACTIVITY = "com.xiaomi.mitv.tvplayer.ExternalSourceActivity";
    private static final String MI_TV_ACTIVITY = "com.xiaomi.mitv.tvplayer.AtvActivity";

    /** Realtek passthrough hardware id used by the original Acer port. */
    public static final String HDMI_2_INPUT_ID =
            "com.realtek.tv.passthrough/.hdmiinput.HDMITvInputService/HW151519744";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static long sLastSwitchTime = 0;

    private HdmiHelper() {}

    public static boolean isMiPlayer(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(MI_PLAYER, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Configured HDMI source ids, in menu order (res/values/config.xml, source_hdmi). */
    public static int[] hdmiSources(Context ctx) {
        int[] ids = ctx.getResources().getIntArray(R.array.source_hdmi);
        return ids == null ? new int[0] : ids;
    }

    /**
     * Labels for {@link #hdmiSources}, index for index (res/values/config.xml, hdmi_labels).
     * Missing labels fall back to "HDMI 1", "HDMI 2" ... so a config that only lists ids still works.
     */
    public static String[] hdmiLabels(Context ctx) {
        int[] ids = hdmiSources(ctx);
        String[] labels = new String[ids.length];
        String[] configured;
        try {
            configured = ctx.getResources().getStringArray(R.array.hdmi_labels);
        } catch (Exception e) {
            configured = new String[0];
        }
        for (int i = 0; i < ids.length; i++) {
            String label = configured != null && i < configured.length ? configured[i] : null;
            labels[i] = label == null || label.trim().isEmpty() ? "HDMI " + (i + 1) : label.trim();
        }
        return labels;
    }

    /** Explicit intent that tunes the Xiaomi TV player to a hardware source id. */
    public static Intent createMiSourceIntent(Context ctx, int source) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(MI_PLAYER, MI_SOURCE_ACTIVITY);
        intent.putExtra("input", source);
        intent.putExtra("no_launch_panel", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    public static Intent createTvIntent(Context ctx) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(MI_PLAYER, MI_TV_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    /** Creates the explicit passthrough intent to display HDMI 2 directly. */
    public static Intent createHdmi2Intent() {
        Uri channelUri = TvContract.buildChannelUriForPassthroughInput(HDMI_2_INPUT_ID);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(channelUri, "vnd.android.cursor.item/channel");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    /**
     * Switches to the HDMI entry at {@code index} of the configured list, with debouncing and an
     * optional toast. Index 1 (the second port) is used when it exists, else the first.
     */
    public static synchronized void switchToHdmi(Context context, int index, boolean showToast) {
        long now = System.currentTimeMillis();
        // Debounce multiple rapid button presses within 500ms
        if (now - sLastSwitchTime < 500) {
            return;
        }
        sLastSwitchTime = now;

        final int[] sources = hdmiSources(context);
        if (sources.length == 0) return;
        final int slot = Math.max(0, Math.min(index, sources.length - 1));
        final String label = hdmiLabels(context)[slot];

        MAIN_HANDLER.post(() -> {
            try {
                if (showToast) {
                    Toast.makeText(context.getApplicationContext(), label, Toast.LENGTH_SHORT).show();
                }
                if (isMiPlayer(context)) {
                    context.startActivity(createMiSourceIntent(context, sources[slot]));
                } else {
                    context.startActivity(createHdmi2Intent());
                }
                Log.i(TAG, "Switched to " + label + " (source id " + sources[slot] + ")");
            } catch (Exception e) {
                Log.e(TAG, "Failed to switch to " + label, e);
                try {
                    Toast.makeText(context.getApplicationContext(),
                            "Cannot open " + label, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
    }

    /** Convenience for the remote hotkeys: the second configured port, else the first. */
    public static void switchToHdmi2(Context context, boolean showToast) {
        switchToHdmi(context, hdmiSources(context).length > 1 ? 1 : 0, showToast);
    }
}
