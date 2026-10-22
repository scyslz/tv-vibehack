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
 *    hardware source id as an "input" int extra. The ids below are the ones this
 *    firmware uses (2 = HDMI 1, 17 = AV, 18 = HDMI 2).
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

    /** Switches to HDMI 2 immediately with debouncing and an optional toast. */
    public static synchronized void switchToHdmi2(Context context, boolean showToast) {
        long now = System.currentTimeMillis();
        // Debounce multiple rapid button presses within 500ms
        if (now - sLastSwitchTime < 500) {
            return;
        }
        sLastSwitchTime = now;

        MAIN_HANDLER.post(() -> {
            try {
                if (showToast) {
                    Toast.makeText(context.getApplicationContext(),
                            "HDMI 2", Toast.LENGTH_SHORT).show();
                }
                if (isMiPlayer(context)) {
                    int[] sources = context.getResources().getIntArray(R.array.source_hdmi);
                    int source = sources.length > 1 ? sources[1] : sources[0];
                    context.startActivity(createMiSourceIntent(context, source));
                } else {
                    context.startActivity(createHdmi2Intent());
                }
                Log.i(TAG, "Switched to HDMI 2");
            } catch (Exception e) {
                Log.e(TAG, "Failed to switch to HDMI 2", e);
                try {
                    Toast.makeText(context.getApplicationContext(),
                            "Cannot open HDMI 2", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
    }
}
