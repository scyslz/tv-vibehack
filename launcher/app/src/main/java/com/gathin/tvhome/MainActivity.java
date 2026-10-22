package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home built around a two-row screen: the pinned dock is the first row of the app list
 * and keeps its tvOS band only while it has focus; moving into the app rows turns it into an
 * ordinary first row of the grid and unfolds one more row underneath. One glass highlight springs
 * between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    /** Height of one tile row: the 78dp tile of item_app.xml plus its 8dp top and bottom padding. */
    private static final int ROW_DP = 94;
    /** Length of one grid page turn; short enough to feel instant, long enough to read as motion. */
    private static final long ROW_SLIDE_MS = 150;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    /** Clips the list to one row (collapsed, dock only) or two rows (expanded). */
    private FrameLayout gridWindow;
    private ViewGroup.LayoutParams windowLp;
    private DockDecoration dockDeco;
    private boolean expanded;
    /** Height of one tile row (tile + its paddings). Two of them is the tallest the screen gets. */
    private int rowPx;
    private ValueAnimator windowAnim;
    private ValueAnimator bandAnim;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView fatherGreeting;
    private TextView festivalText;
    private ImageView wallpaper;
    private TextView btnWifi;
    private TextView btnBluetooth;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    private int lastFocused = 0;
    /** Tile that owns the glass highlight, so it can follow the row when the window resizes. */
    private View focusedCard;
    /** Set when the rows were moved without a scroll event; the glass is re-glued before drawing. */
    private boolean glassDirty;
    private float density;

    /** Bluetooth state: the pill shows the name of the connected accessory, not just "Bluetooth". */
    private BluetoothManager btManager;
    private final Map<Integer, BluetoothProfile> btProfiles = new HashMap<>();
    private final Map<String, String> btConnected = new HashMap<>();
    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null) {
                String address = device.getAddress();
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    String name = safeDeviceName(device);
                    if (name != null) btConnected.put(address, name);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    btConnected.remove(address);
                }
            }
            updateBluetoothLabel();
        }
    };
    /** Fires on every connectivity / Wi-Fi state change so the pill never shows a stale network. */
    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateWifiLabel();
        }
    };
    private ConnectivityManager.NetworkCallback wifiCallback;
    /** Boot can finish before Wi-Fi associates; poll a few times instead of waiting a whole minute. */
    private int wifiRetries;
    private final Runnable wifiRetry = new Runnable() {
        @Override
        public void run() {
            updateWifiLabel();
        }
    };

    private final BluetoothProfile.ServiceListener btProfileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            btProfiles.put(profile, proxy);
            updateBluetoothLabel();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            btProfiles.remove(profile);
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateFatherGreeting();
            updateFestivalGreeting();
            updateWifiLabel();
            updateBluetoothLabel();
            updateWallpaper(true);
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        fatherGreeting = findViewById(R.id.fatherGreeting);
        festivalText = findViewById(R.id.festivalText);
        updateFatherGreeting();
        updateFestivalGreeting();
        btnWifi = findViewById(R.id.btnWifi);
        wallpaper = findViewById(R.id.wallpaper);
        updateWallpaper(false);
        BootReceiver.ensureAccessibilityEnabled(this);

        grid = findViewById(R.id.grid);
        gridWindow = findViewById(R.id.gridWindow);
        rowPx = Math.round(ROW_DP * density);
        // The list is one row taller than the window, so the next row is always laid out: pressing
        // down can hand the focus over immediately and let the rows slide underneath the cursor.
        ViewGroup.LayoutParams gridLp = grid.getLayoutParams();
        gridLp.height = windowHeight(true) + rowPx;
        grid.setLayoutParams(gridLp);
        windowLp = gridWindow.getLayoutParams();
        windowLp.height = windowHeight(false);

        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        dockDeco = new DockDecoration(density);
        grid.addItemDecoration(dockDeco);
        adapter = new AppAdapter(this, this);
        grid.setAdapter(adapter);

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Row snaps and window resizes move the tiles without a scroll event, so re-glue the
        // highlight once the new layout is in place instead of leaving it on an empty spot.
        grid.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (glassDirty) {
                glassDirty = false;
                syncGlassToFocus();
            }
            return true;
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(430f).setDampingRatio(0.82f));
        }

        btnBluetooth = findViewById(R.id.btnBluetooth);
        setupBluetooth();
        setupWifiMonitor();
        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openTvScreen(TvSettings.wifi(this)));
        btnBluetooth.setOnClickListener(v -> openTvScreen(TvSettings.bluetooth(this)));
        btnSettings.setOnClickListener(v -> openTvScreen(TvSettings.settings(this)));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnBluetooth.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // The drawableTint selector does not refresh compound-drawable icons on this TV, so tint
        // them by hand: dark on the warm highlight, white on the translucent pill.
        for (TextView pill : new TextView[]{(TextView) btnInputs, btnWifi, btnBluetooth, (TextView) btnSettings}) {
            pill.setOnFocusChangeListener((v, focused) -> {
                tintPill((TextView) v, focused);
                if (focused) setExpanded(false, true);   // back to the one-row dock view
            });
            tintPill(pill, false);
        }

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        maybeOfferDefaultHome();
    }

    /**
     * Home key interception works without special privileges, but the TV first draws its own
     * launcher and then this one takes over (a few seconds). Setting this app as the system home
     * screen removes that delay. The stock launcher is never disabled, so this stays reversible:
     * Settings -> Apps -> Default apps (or uninstalling this app) brings the old home back.
     */
    private void maybeOfferDefaultHome() {
        android.content.SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        if (prefs.getBoolean("asked_default_home", false)) return;
        prefs.edit().putBoolean("asked_default_home", true).apply();

        new AlertDialog.Builder(this)
                .setTitle(R.string.set_home_title)
                .setMessage(R.string.set_home_message)
                .setPositiveButton(R.string.set_home_ok, (d, w) -> openTvScreen(TvSettings.settings(this)))
                .setNegativeButton(R.string.set_home_later, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        adapter.reload();
        resetWindow();
        grid.scheduleLayoutAnimation();
        updateGreeting();
        updateFatherGreeting();
        updateFestivalGreeting();
        updateWifiLabel();
        updateBluetoothLabel();
        updateWallpaper(true);
        BootReceiver.ensureAccessibilityEnabled(this);
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the tile we left from, or the first one.
        grid.post(() -> {
            adoptMeasuredRow();
            View focused = getCurrentFocus();
            if (focused != null && grid.findContainingViewHolder(focused) != null) return;
            focusTile(Math.max(0, lastFocused));
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(btReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(wifiReceiver);
        } catch (Exception ignored) {
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && wifiCallback != null) cm.unregisterNetworkCallback(wifiCallback);
        } catch (Exception ignored) {
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            for (Map.Entry<Integer, BluetoothProfile> e : btProfiles.entrySet()) {
                try {
                    adapter.closeProfileProxy(e.getKey(), e.getValue());
                } catch (Exception ignored) {
                }
            }
        }
        btProfiles.clear();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (grid != null) {
            grid.post(() -> focusTile(lastFocused));
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        focusedCard = card;
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        gridWindow.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }

        // The dock row keeps its band while it has focus; moving into the app rows turns it into a
        // plain first row of the grid and unfolds the second row.
        boolean dockRow = adapter.isDockPosition(position);
        setExpanded(!dockRow, true);
        showAmbient(position);
    }

    /**
     * First row of the list that should sit at the top of the window: expanded, the focused row is
     * the bottom one and the row above it is the top one; collapsed, the dock row is the only one.
     */
    private int topRowFor(int position) {
        int row = position / COLUMNS;
        return expanded ? Math.max(0, row - 1) : row;
    }

    /** Jumps the list to a row boundary without animating (used for far jumps and restores). */
    private void snapRowsTo(int position) {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (!(lm instanceof GridLayoutManager)) return;
        ((GridLayoutManager) lm).scrollToPositionWithOffset(topRowFor(position) * COLUMNS, 0);
        glassDirty = true;
    }

    /** How far the list is scrolled, in pixels; rows are uniform, so this inverts cleanly. */
    private int scrollOffset() {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (!(lm instanceof GridLayoutManager)) return 0;
        GridLayoutManager glm = (GridLayoutManager) lm;
        int first = glm.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) return 0;
        View v = glm.findViewByPosition(first);
        if (v == null) return 0;
        return (first / COLUMNS) * rowPx - v.getTop();
    }


    // ---- top shelf -------------------------------------------------------------------------------

    /** Tint the ambient glow to match the focused tile; no big title above the tiles. */
    private void showAmbient(int position) {
        if (heroShown) {
            heroShown = false;
            heroName.animate().alpha(0f).setDuration(160).start();
        }
        setAmbient(adapter.ambientColorAt(position));
        ambient.animate().alpha(1f).setDuration(260).start();
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- one row / two rows ----------------------------------------------------------------------

    /**
     * The screen never shows more than two rows. Collapsed: only the dock row, as a dock.
     * Expanded: the dock row loses its band and becomes the first row of the grid, with the app row
     * the user is on right below it.
     */
    private void setExpanded(boolean want, boolean animate) {
        if (want == expanded) return;
        expanded = want;
        animateWindow(want ? windowHeight(true) : windowHeight(false), animate);
        animateBand(want ? 0f : 1f, animate);
        // Back to one row: that row has to be the dock again, not whichever app row was on screen.
        if (!want) {
            grid.scrollToPosition(0);
            glassDirty = true;
        }
    }

    /** Puts the screen back in its resting state (one row = the dock, or two when no dock yet). */
    private void resetWindow() {
        boolean hasDock = adapter.dockCount() > 0;
        expanded = !hasDock;
        if (windowAnim != null) windowAnim.cancel();
        if (bandAnim != null) bandAnim.cancel();
        windowLp.height = windowHeight(expanded);
        gridWindow.setLayoutParams(windowLp);
        dockDeco.setAlpha(hasDock ? 1f : 0f);
        grid.invalidate();   // NOT invalidateItemDecorations(): that throws during scroll / layout
        if (!expanded) grid.scrollToPosition(0);
        glassDirty = true;
    }

    /**
     * Rows are height-defined by {@code item_app.xml}; if the layout ever measures them differently
     * (bigger type scale, another tile size) the window follows the real height instead of clipping
     * a sliver of the next row.
     */
    private void adoptMeasuredRow() {
        if (grid.getChildCount() == 0) return;
        int measured = grid.getChildAt(0).getHeight();
        if (measured <= 0 || measured == rowPx) return;
        rowPx = measured;
        ViewGroup.LayoutParams gridLp = grid.getLayoutParams();
        gridLp.height = windowHeight(true) + rowPx;
        grid.setLayoutParams(gridLp);
        if (windowAnim == null || !windowAnim.isRunning()) {
            windowLp.height = windowHeight(expanded);
            gridWindow.setLayoutParams(windowLp);
        }
    }

    private int windowHeight(boolean twoRows) {
        return (twoRows ? 2 : 1) * rowPx;
    }

    private void animateWindow(int target, boolean animate) {
        if (windowAnim != null) windowAnim.cancel();
        if (!animate || gridWindow.getHeight() == 0) {
            windowLp.height = target;
            gridWindow.setLayoutParams(windowLp);
            glassDirty = true;
            return;
        }
        windowAnim = ValueAnimator.ofInt(gridWindow.getHeight(), target);
        windowAnim.setDuration(170);
        windowAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        windowAnim.addUpdateListener(a -> {
            windowLp.height = (int) a.getAnimatedValue();
            gridWindow.setLayoutParams(windowLp);
            // The block is bottom-anchored, so its rows slide while the window grows / shrinks.
            glassDirty = true;
        });
        windowAnim.start();
    }

    /**
     * Re-glues the glass highlight to the focused tile after the window changed height. The dock row
     * moving under a fixed highlight is what looked like an empty tile popping up.
     */
    private void syncGlassToFocus() {
        if (!glassShown || focusedCard == null || focusedCard.getParent() == null) return;
        Rect r = new Rect(0, 0, focusedCard.getWidth(), focusedCard.getHeight());
        gridWindow.offsetDescendantRectToMyCoords(focusedCard, r);
        float pad = 6f * density;
        float w = focusedCard.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = focusedCard.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        glassTargetX = r.exactCenterX() - w / 2f;
        glassTargetY = r.exactCenterY() - h / 2f;
        springX.cancel();
        springY.cancel();
        glass.setTranslationX(glassTargetX);
        glass.setTranslationY(glassTargetY);
    }

    /** Fades the dock band out (grid row) and back in (dock row). */
    private void animateBand(float target, boolean animate) {
        if (bandAnim != null) bandAnim.cancel();
        if (!animate) {
            dockDeco.setAlpha(target);
            grid.invalidate();   // NOT invalidateItemDecorations(): that throws during scroll / layout
            return;
        }
        bandAnim = ValueAnimator.ofFloat(dockDeco.getAlpha(), target);
        bandAnim.setDuration(200);
        bandAnim.addUpdateListener(a -> {
            dockDeco.setAlpha((float) a.getAnimatedValue());
            grid.invalidate();   // NOT invalidateItemDecorations(): that throws during scroll / layout
        });
        bandAnim.start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    /**
     * Long-press of OK on a tile: pin it to the dock, or unpin it. New pins are appended, so the
     * dock keeps the order in which the tiles were added.
     */
    @Override
    public boolean onTileLongPress(int position, View card) {
        String key = adapter.keyAt(position);
        if (key == null) return true;
        boolean inDock = adapter.isDockPosition(position);
        String label = adapter.pinnedLabel(key);

        View panel = getLayoutInflater().inflate(R.layout.popup_tile_menu, root, false);
        ((TextView) panel.findViewById(R.id.panelTitle)).setText(label);
        LinearLayout rows = panel.findViewById(R.id.rows);

        final PopupWindow popup =
                new PopupWindow(panel, (int) (220 * density), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);

        if (inDock) {
            addMenuRow(rows, popup, R.drawable.ic_unpin, "从 Dock 移除", () -> {
                dockStore().remove(key);
                refreshTiles(key);
                Toast.makeText(this, "已从 Dock 移除：" + label, Toast.LENGTH_SHORT).show();
            });
        } else {
            addMenuRow(rows, popup, R.drawable.ic_pin, "固定到 Dock", () -> {
                if (dockStore().size() >= AppAdapter.MAX_DOCK) {
                    Toast.makeText(this, "Dock 只能放 " + AppAdapter.MAX_DOCK + " 个，请先移除一个",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                dockStore().add(key);
                refreshTiles(key);
                Toast.makeText(this, "已固定到 Dock：" + label, Toast.LENGTH_SHORT).show();
            });
        }

        // Real, removable apps also get an uninstall entry (inputs / built-in tiles / system apps do not).
        String uninstallPkg = adapter.uninstallablePackage(key);
        if (uninstallPkg != null) {
            addSeparator(rows);
            addMenuRow(rows, popup, R.drawable.ic_uninstall, getString(R.string.uninstall),
                    () -> confirmUninstall(key, label, uninstallPkg));
        }

        int[] loc = new int[2];
        card.getLocationOnScreen(loc);
        popup.showAtLocation(root, Gravity.NO_GRAVITY, loc[0] - (int) (12 * density),
                loc[1] + card.getHeight() - (int) (6 * density));
        rows.getChildAt(0).requestFocus();
        return true;
    }

    // ---- uninstall -------------------------------------------------------------------------------

    private static final int REQ_UNINSTALL = 201;
    private String pendingUninstallKey;
    private String pendingUninstallPkg;
    private String pendingUninstallLabel;

    /**
     * Second confirmation before handing the uninstall to the system package installer.
     * The system installer shows its own final prompt, so the user has to confirm twice.
     */
    private void confirmUninstall(String key, String label, String pkg) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.uninstall_title)
                .setMessage(getString(R.string.uninstall_message, label))
                .setPositiveButton(R.string.uninstall_confirm, (d, w) -> {
                    pendingUninstallKey = key;
                    pendingUninstallPkg = pkg;
                    pendingUninstallLabel = label;
                    Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                    try {
                        startActivityForResult(intent, REQ_UNINSTALL);
                    } catch (ActivityNotFoundException ex) {
                        clearPendingUninstall();
                        Toast.makeText(this, R.string.uninstall_no_installer, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.uninstall_cancel, null)
                .show();
    }

    private void clearPendingUninstall() {
        pendingUninstallKey = null;
        pendingUninstallPkg = null;
        pendingUninstallLabel = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_UNINSTALL || pendingUninstallPkg == null) return;

        boolean stillInstalled;
        try {
            getPackageManager().getPackageInfo(pendingUninstallPkg, 0);
            stillInstalled = true;
        } catch (PackageManager.NameNotFoundException ex) {
            stillInstalled = false;
        }

        if (!stillInstalled) {
            // Drop the tile from the dock list as well, so it can never come back as a hole.
            dockStore().remove(pendingUninstallKey);
            String label = pendingUninstallLabel;
            clearPendingUninstall();
            refreshTiles(null);
            Toast.makeText(this, getString(R.string.uninstall_done, label), Toast.LENGTH_SHORT).show();
        } else {
            // User cancelled in the system installer.
            clearPendingUninstall();
        }
    }

    private DockStore dockStore;

    private DockStore dockStore() {
        if (dockStore == null) dockStore = new DockStore(this);
        return dockStore;
    }

    /** Rebuilds the list and keeps the focus on the tile the user just pinned / unpinned. */
    private void refreshTiles(String focusKey) {
        adapter.refresh();
        final int wanted = focusKey != null ? adapter.positionOfKey(focusKey) : -1;
        grid.post(() -> {
            if (adapter.realCount() == 0) return;
            adoptMeasuredRow();
            if (wanted >= 0) {
                focusTile(wanted);
                return;
            }
            if (lastFocused >= adapter.realCount()) {
                lastFocused = Math.max(0, adapter.realCount() - 1);
            }
            focusTile(lastFocused);
        });
    }

    /**
     * Every row boundary is on a multiple of {@link #COLUMNS}, so up / down is exactly one row and
     * left / right never leaves the row. The dock row is the first one; moving down into the app
     * rows unfolds the second row, moving back up folds it away again.
     */
    @Override
    public boolean onTileKey(int position, int keyCode) {
        int count = adapter.realCount();
        int col = position % COLUMNS;
        int row = position / COLUMNS;
        boolean inDock = adapter.isDockPosition(position);

        // ---- the dock row: entered and left with UP / DOWN only ----
        if (inDock) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                focusTopBar();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusTile(position + COLUMNS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (col > 0) focusTile(position - 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // Stays inside the pinned tiles: the grid below is opened by pressing DOWN.
                if (position + 1 < adapter.dockCount()) focusTile(position + 1);
                return true;
            }
            return false;
        }

        // ---- app rows ----
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            int above = position - COLUMNS;
            if (above < 0) {
                focusTopBar();
                return true;
            }
            // Land on a real dock tile when the column above is an empty dock slot.
            if (adapter.isDockRow(above)) above = Math.min(above, adapter.dockCount() - 1);
            focusTile(above);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int below = position + COLUMNS;
            if (below >= count) {
                // Last row: nothing below. Otherwise the row below is shorter than this column,
                // so land on its nearest tile instead of swallowing the press.
                if (row >= (count - 1) / COLUMNS) return true;
                below = count - 1;
            }
            focusTile(below);
            return true;
        }

        // Left / right also change rows at the ends of a row, like any grid. The dock row above is
        // skipped on purpose: it is not a grid row, UP is what goes back into it.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (col > 0) {
                focusTile(position - 1);
                return true;
            }
            if (position > 0 && !adapter.isDockRow(position - 1)) focusTile(position - 1);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (col < COLUMNS - 1 && position + 1 < count) {
                focusTile(position + 1);
                return true;
            }
            int nextRowStart = (row + 1) * COLUMNS;
            if (nextRowStart < count) focusTile(nextRowStart);
            return true;
        }
        return false;
    }

    /** Up from the top row: back to the pills, which also folds the second row away. */
    private void focusTopBar() {
        setExpanded(false, true);
        findViewById(R.id.btnInputs).requestFocus();
    }

    private void focusTile(int position) {
        if (adapter.realCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.realCount() - 1));
        setExpanded(!adapter.isDockPosition(pos), true);

        final int dy = topRowFor(pos) * rowPx - scrollOffset();
        if (dy == 0) {
            focusOrPost(pos);
            return;
        }
        if (Math.abs(dy) > rowPx) {
            // Too far for a one-row page turn (restore, pin / unpin): land there without animating.
            snapRowsTo(pos);
            focusOrPost(pos);
            return;
        }

        // The next row is always laid out, so the cursor moves first and the rows slide under it —
        // that reads as one motion instead of "wait, then jump".
        if (requestFocusAt(pos)) {
            slideRows(dy);
            return;
        }
        grid.postDelayed(() -> {
            if (requestFocusAt(pos)) slideRows(topRowFor(pos) * rowPx - scrollOffset());
            else grid.postDelayed(() -> {
                if (!requestFocusAt(pos)) focusOrPost(pos);
            }, 60);
        }, 32);
    }

    /**
     * Slides one page turn, landing exactly on a row edge. The list is one row taller than the
     * window and carries two empty rows at the end, so RecyclerView can always scroll by exactly
     * one row and stop on the boundary.
     */
    private void slideRows(int dy) {
        if (dy == 0) return;
        grid.smoothScrollBy(0, dy);
    }

    private void focusOrPost(int position) {
        if (requestFocusAt(position)) return;
        grid.post(() -> {
            if (!requestFocusAt(position)) grid.postDelayed(() -> requestFocusAt(position), 120);
        });
    }

    private boolean requestFocusAt(int position) {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        if (vh == null) return false;
        View card = vh.itemView.findViewById(R.id.card);
        if (card == null || !card.isShown()) return false;
        card.requestFocus();
        return true;
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** NLRC brand is set statically in layout; this method is kept for lifecycle compatibility. */
    private void updateGreeting() {
        // No-op: NLRC brand name is declared in activity_main.xml — no runtime greeting needed.
    }

    /** Time-of-day greeting in Chinese. */
    private void updateFatherGreeting() {
        if (fatherGreeting == null) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);

        String wish;
        if (hour >= 5 && hour < 11) {
            wish = "\u65e9\u4e0a\u597d \u00b7 \u6b22\u8fce\u56de\u5bb6 \ud83d\ude4f";
        } else if (hour >= 11 && hour < 13) {
            wish = "\u4e2d\u5348\u597d \u00b7 \u6b22\u8fce\u56de\u5bb6 \ud83d\ude4f";
        } else if (hour >= 13 && hour < 18) {
            wish = "\u4e0b\u5348\u597d \u00b7 \u6b22\u8fce\u56de\u5bb6 \ud83d\ude4f";
        } else if (hour >= 18 && hour < 23) {
            wish = "\u665a\u4e0a\u597d \u00b7 \u6b22\u8fce\u56de\u5bb6 \ud83d\ude4f";
        } else {
            wish = "\u591c\u6df1\u4e86 \u00b7 \u6ce8\u610f\u4f11\u606f \ud83c\udf19";
        }
        fatherGreeting.setText(wish);
    }

    /** Chinese festival / solar-term wish for today, with the weekday. */
    private void updateFestivalGreeting() {
        if (festivalText == null) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK);

        String week;
        switch (dayOfWeek) {
            case java.util.Calendar.SUNDAY:    week = "\u661f\u671f\u65e5"; break;
            case java.util.Calendar.MONDAY:    week = "\u661f\u671f\u4e00"; break;
            case java.util.Calendar.TUESDAY:   week = "\u661f\u671f\u4e8c"; break;
            case java.util.Calendar.WEDNESDAY: week = "\u661f\u671f\u4e09"; break;
            case java.util.Calendar.THURSDAY:  week = "\u661f\u671f\u56db"; break;
            case java.util.Calendar.FRIDAY:    week = "\u661f\u671f\u4e94"; break;
            case java.util.Calendar.SATURDAY:  week = "\u661f\u671f\u516d"; break;
            default: week = "";
        }

        String greeting;
        if (month == 1 && day == 1) {
            greeting = "\ud83e\udde8 \u5143\u65e6 \u00b7 \u65b0\u5e74\u5feb\u4e50 \u00b7 " + week + " \ud83e\udde8";
        } else if (month == 2 && day >= 14 && day <= 21) {
            greeting = "\ud83c\udfee \u6625\u8282\u5feb\u4e50 \u00b7 \u4e07\u4e8b\u5982\u610f \u00b7 " + week + " \ud83c\udfee";
        } else if (month == 2 && day >= 1 && day <= 13) {
            greeting = "\ud83c\udf8f \u65b0\u6625\u4f73\u8282 \u00b7 \u56e2\u56e2\u5706\u5706 \u00b7 " + week + " \ud83c\udf8f";
        } else if (month == 3 && day <= 5) {
            greeting = "\ud83c\udf3e \u60ca\u86f0 \u00b7 \u6625\u610f\u76ce\u7136 \u00b7 " + week + " \ud83c\udf3e";
        } else if (month == 4 && day >= 3 && day <= 6) {
            greeting = "\ud83c\udf3e \u6e05\u660e \u00b7 \u6000\u5ff5\u6545\u4eba \u00b7 " + week + " \ud83c\udf3e";
        } else if (month == 5 && day >= 1 && day <= 3) {
            greeting = "\ud83c\udf89 \u52b3\u52a8\u8282 \u00b7 \u5047\u671f\u6109\u5feb \u00b7 " + week + " \ud83c\udf89";
        } else if (month == 6 && day >= 18 && day <= 24) {
            greeting = "\ud83d\udc09 \u7aef\u5348\u5b89\u5eb7 \u00b7 \u54b8\u86cb\u7ca4\u9999 \u00b7 " + week + " \ud83d\udc09";
        } else if (month == 7 && day == 1) {
            greeting = "\ud83c\udf89 \u5efa\u515a\u8282 \u00b7 " + week + " \ud83c\udf89";
        } else if (month == 8 && day == 1) {
            greeting = "\ud83c\udf96\ufe0f \u5efa\u519b\u8282 \u00b7 " + week + " \ud83c\udf96\ufe0f";
        } else if (month == 9 && day >= 14 && day <= 21) {
            greeting = "\ud83c\udf15 \u4e2d\u79cb\u8282 \u00b7 \u82b1\u597d\u6708\u5706 \u00b7 " + week + " \ud83c\udf15";
        } else if (month == 10 && day >= 1 && day <= 7) {
            greeting = "\ud83c\udf89 \u56fd\u5e86\u8282 \u00b7 \u76db\u4e16\u534e\u8bde \u00b7 " + week + " \ud83c\udf89";
        } else if (month == 10 && day >= 8 && day <= 10) {
            greeting = "\ud83c\udf32 \u91cd\u9633\u8282 \u00b7 \u957f\u957f\u4e45\u4e45 \u00b7 " + week + " \ud83c\udf32";
        } else if (month == 12 && day >= 21 && day <= 23) {
            greeting = "\ud83e\udd5f \u51ac\u81f3 \u00b7 \u4eba\u95f4\u5c0f\u56e2\u5706 \u00b7 " + week + " \ud83e\udd5f";
        } else if (month == 12 && day >= 24 && day <= 26) {
            greeting = "\ud83c\udf84 \u5723\u8bde\u5feb\u4e50 \u00b7 " + week + " \ud83c\udf84";
        } else {
            greeting = "\ud83c\udfe1 \u6b22\u8fce\u56de\u5bb6 \u00b7 \u613f\u4f60\u6709\u4e2a\u6109\u5feb\u7684\u4e00\u5929 \u00b7 " + week;
        }

        festivalText.setText(greeting);
    }

    /**
     * Rotates the background photo. The image only changes when the wallpaper slot changes, so
     * repeated onResume calls cost nothing; the crossfade is skipped on the very first paint.
     */
    private void updateWallpaper(boolean animate) {
        Wallpapers.apply(this, wallpaper, animate);
    }

    /**
     * Shows the Wi-Fi network name on the pill, or the connection type when there is no name.
     *
     * <p>Reading the network once is not enough on this TV: the launcher is started by the boot
     * broadcast before Wi-Fi has associated, so the very first read returns "offline" and nothing
     * refreshed it for up to a minute. Every connectivity change now updates the pill, and while it
     * is still offline a few short retries run so the SSID appears within a second of associating.
     */
    private void updateWifiLabel() {
        if (btnWifi == null) return;
        String text = currentWifiLabel();
        btnWifi.setText(text);

        boolean offline = text.equals(getString(R.string.offline));
        handler.removeCallbacks(wifiRetry);
        if (offline && wifiRetries < 12) {
            wifiRetries++;
            handler.postDelayed(wifiRetry, 1200);
        } else if (!offline) {
            wifiRetries = 0;
        }
    }

    private String currentWifiLabel() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return getString(R.string.ethernet);
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    String ssid = currentSsid();
                    return ssid != null ? ssid : getString(R.string.wifi);
                }
                return getString(R.string.wifi);   // VPN or another transport: still online
            }
            // Some firmwares leave getActiveNetwork() empty right after boot; ask the legacy API.
            NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
            if (info != null && info.isConnected()) {
                if (info.getType() == ConnectivityManager.TYPE_ETHERNET) return getString(R.string.ethernet);
                String ssid = currentSsid();
                return ssid != null ? ssid : getString(R.string.wifi);
            }
            // Last resort: the Wi-Fi link can be associated before ConnectivityManager catches up.
            String ssid = currentSsid();
            if (ssid != null) return ssid;
        } catch (Exception ignored) {
        }
        return getString(R.string.offline);
    }

    /** SSID of the current Wi-Fi link, or null when there is none / it cannot be read. */
    private String currentSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null) return null;
            ssid = ssid.replace("\"", "").trim();
            if (ssid.isEmpty() || ssid.toLowerCase().contains("unknown") || ssid.equals("0x")) return null;
            return ssid;
        } catch (Exception e) {
            return null;
        }
    }

    /** Connectivity + Wi-Fi state listeners; a single missed broadcast used to leave "offline" stuck. */
    private void setupWifiMonitor() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            registerReceiver(wifiReceiver, filter);
        } catch (Exception ignored) {
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                wifiCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        updateWifiLabel();
                    }

                    @Override
                    public void onLost(Network network) {
                        updateWifiLabel();
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        updateWifiLabel();
                    }
                };
                cm.registerNetworkCallback(new NetworkRequest.Builder().build(), wifiCallback);
            }
        } catch (Exception ignored) {
        }
        updateWifiLabel();
    }

    /** Dark glyphs on the warm focus pill, white glyphs on the translucent one. */
    private static void tintPill(TextView pill, boolean focused) {
        int color = focused ? 0xFF141414 : 0xFFFFFFFF;
        pill.setTextColor(color);
        for (Drawable d : pill.getCompoundDrawablesRelative()) {
            if (d != null) d.setTint(color);
        }
    }

    /** Same idea for the rows of the Inputs / long-press menus. */
    private static void bindRowFocusTint(View row, TextView title, ImageView icon) {
        row.setOnFocusChangeListener((v, focused) -> {
            int color = focused ? 0xFF141414 : 0xFFFFFFFF;
            title.setTextColor(color);
            icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            TextView status = row.findViewById(R.id.status);
            if (status != null) status.setTextColor(focused ? 0x99141414 : 0x73FFFFFF);
        });
    }

    /** Shows the name of the connected Bluetooth accessory, or a short state when nothing is connected. */
    private void updateBluetoothLabel() {
        if (btnBluetooth == null) return;
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        String text;
        if (a == null || !a.isEnabled()) {
            text = getString(R.string.bluetooth_off);
        } else {
            String name = connectedBluetoothName(a);
            text = name != null ? name : getString(R.string.not_connected);
        }
        btnBluetooth.setText(text);
    }

    private void setupBluetooth() {
        btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a != null) {
            // A2DP / HEADSET proxies report speakers, headphones and soundbars as connected.
            for (int profile : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET}) {
                try {
                    a.getProfileProxy(this, btProfileListener, profile);
                } catch (Exception ignored) {
                }
            }
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        try {
            registerReceiver(btReceiver, filter);
        } catch (Exception ignored) {
        }
        updateBluetoothLabel();
    }

    /**
     * Returns a display name for the accessory currently connected over Bluetooth, or null.
     * Several sources are merged because TV ROMs differ in which ones they report.
     */
    private String connectedBluetoothName(BluetoothAdapter adapter) {
        // Audio accessories (headphones, soundbars) are what people care about in the top bar, so
        // they win over the remote control that is always connected over GATT.
        for (int profile : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET}) {
            BluetoothProfile proxy = btProfiles.get(profile);
            if (proxy == null) continue;
            try {
                String name = firstName(proxy.getConnectedDevices());
                if (name != null) return name;
            } catch (Exception ignored) {
            }
        }
        Set<String> names = new LinkedHashSet<>();
        names.addAll(btConnected.values());
        if (btManager != null) {
            for (int profile : new int[]{BluetoothProfile.GATT, BluetoothProfile.GATT_SERVER}) {
                try {
                    collectNames(btManager.getConnectedDevices(profile), names);
                } catch (Exception ignored) {
                }
            }
        }
        for (BluetoothProfile proxy : btProfiles.values()) {
            try {
                collectNames(proxy.getConnectedDevices(), names);
            } catch (Exception ignored) {
            }
        }
        if (names.isEmpty()) {
            // Some ROMs only expose the connection state on the bonded device itself.
            try {
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    if (isConnectedHidden(d)) {
                        String n = safeDeviceName(d);
                        if (n != null) names.add(n);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String best = null;
        for (String n : names) {
            if (n == null || n.trim().isEmpty()) continue;
            // Prefer a real product name; fall back to whatever else is there.
            if (best == null || isGenericName(best)) best = n.trim();
        }
        return best;
    }

    private static String firstName(List<BluetoothDevice> devices) {
        if (devices == null) return null;
        for (BluetoothDevice d : devices) {
            String n = safeDeviceName(d);
            if (n != null && !isGenericName(n)) return n;
        }
        return null;
    }

    private void collectNames(List<BluetoothDevice> devices, Set<String> out) {
        if (devices == null) return;
        for (BluetoothDevice d : devices) {
            String n = safeDeviceName(d);
            if (n != null) out.add(n);
        }
    }

    private static boolean isGenericName(String name) {
        return name.equalsIgnoreCase("Bluetooth") || name.equalsIgnoreCase("Bluetooth device")
                || name.startsWith("00:") || name.matches("[0-9A-Fa-f:]{17}");
    }

    private static String safeDeviceName(BluetoothDevice device) {
        if (device == null) return null;
        try {
            String name = device.getName();
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (Exception ignored) {
        }
        try {
            return device.getAddress(); // last resort: address beats showing nothing
        } catch (Exception e) {
            return null;
        }
    }

    /** BluetoothDevice#isConnected is hidden; ROMs that lack profile support still answer it. */
    private static boolean isConnectedHidden(BluetoothDevice device) {
        try {
            java.lang.reflect.Method m = BluetoothDevice.class.getMethod("isConnected");
            Object result = m.invoke(device);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opens a vendor settings screen, showing a message if this TV has none of them. */
    private void openTvScreen(Intent i) {
        if (i == null) {
            Toast.makeText(this, R.string.screen_not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        open(i);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        boolean sawNonHdmi = false;
        if (HdmiHelper.isMiPlayer(this)) {
            // Every configured source is always listed here, whether or not it has a dock tile.
            int[] hdmi = getResources().getIntArray(R.array.source_hdmi);
            for (int i = 0; i < hdmi.length; i++) {
                final int source = hdmi[i];
                final String label = "HDMI " + (i + 1);
                addInputRow(rows, popup, R.drawable.ic_input, label, -1, "hdmi_" + (i + 1),
                        () -> open(HdmiHelper.createMiSourceIntent(this, source)));
            }
            addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_av, "AV Input", -1, "av_input",
                    () -> open(HdmiHelper.createMiSourceIntent(this,
                            getResources().getInteger(R.integer.source_av))));
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, "live_tv",
                    () -> open(HdmiHelper.createTvIntent(this)));
        } else {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                String key = hdmi ? "hdmi_" + label.replaceAll("[^0-9]", "") : "av_input";
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state, key,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        }
        Intent liveTv = HdmiHelper.isMiPlayer(this) || liveTvPkg.isEmpty()
                ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1,
                    liveTvPkg, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    /** One focusable entry of the pin / unpin menu. */
    private void addMenuRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        bindRowFocusTint(row, (TextView) row.findViewById(R.id.title),
                (ImageView) row.findViewById(R.id.icon));
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state,
                             String dockKey, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        bindRowFocusTint(row, (TextView) row.findViewById(R.id.title),
                (ImageView) row.findViewById(R.id.icon));
        // Long press pins this source to the dock (or unpins it).
        row.setOnLongClickListener(v -> {
            boolean pinned = dockStore().contains(dockKey);
            if (!pinned && dockStore().size() >= AppAdapter.MAX_DOCK) {
                Toast.makeText(this, "Dock 只能放 " + AppAdapter.MAX_DOCK + " 个，请先移除一个",
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            if (pinned) dockStore().remove(dockKey);
            else dockStore().add(dockKey);
            refreshTiles(null);
            popup.dismiss();
            Toast.makeText(this, (pinned ? "已从 Dock 移除：" : "已固定到 Dock：") + title,
                    Toast.LENGTH_SHORT).show();
            return true;
        });
        rows.addView(row);
    }
}
