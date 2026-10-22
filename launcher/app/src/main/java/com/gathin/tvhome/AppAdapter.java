package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One flat list for the whole home screen: the pinned dock tiles are its first row, the app tiles
 * follow. Because the dock is the first row of the same grid, it can stop looking like a dock the
 * moment the user moves down into the app rows (see {@link DockDecoration} and
 * {@link MainActivity#setExpanded(boolean, boolean)}), which keeps the screen at two rows.
 *
 * <p>The dock row is padded with invisible placeholder slots so every real tile starts on a row
 * boundary: {@code position ± columns} is always "one row up / down".
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);
        boolean onTileKey(int position, int keyCode);
        /** Long-press of OK: opens the pin / unpin menu for the tile. */
        boolean onTileLongPress(int position, View card);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        final Intent launchIntent;
        final boolean isInput;
        int ambient;

        /** Regular application tile. */
        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label        = label;
            this.component    = component;
            this.pkg          = component.getPackageName();
            this.banner       = banner;
            this.icon         = icon;
            this.launchIntent = null;
            this.isInput      = false;
        }

        /** TV Input tile (HDMI 1 / Jio TV, HDMI 2, AV). */
        Entry(String label, String pkgKey, Drawable banner, Drawable icon, Intent launchIntent, int ambientColor) {
            this.label        = label;
            this.component    = null;
            this.pkg          = pkgKey;
            this.banner       = banner;
            this.icon         = icon;
            this.launchIntent = launchIntent;
            this.isInput      = true;
            this.ambient      = ambientColor;
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private final Context ctx;
    private final TileListener listener;
    private final DockStore dockStore;
    private final Set<String> hidden;
    /** Tiles per row; the dock row is exactly this many slots wide. */
    private final int columns;

    /** Tiles shown in the dock, in the order the user pinned them (max {@link #MAX_DOCK}). */
    private final List<Entry> dockItems = new ArrayList<>();
    /** Tiles shown in the app grid: apps only, never inputs, never dock tiles. */
    private final List<Entry> gridItems = new ArrayList<>();
    /** What the list actually shows: dock, invisible placeholder slots, then the apps. */
    private final List<Entry> items = new ArrayList<>();

    /** A dock row holds at most one full row of tiles. */
    static int MAX_DOCK = 4;

    AppAdapter(Context ctx, TileListener listener) {
        this.ctx       = ctx;
        this.listener  = listener;
        this.dockStore = new DockStore(ctx);
        this.hidden    = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        this.columns   = Math.max(1, ctx.getResources().getInteger(R.integer.columns));
        MAX_DOCK       = Math.min(4, columns);
    }

    /** Rebuilds the tile list from the package manager. */
    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);

        List<Entry> fresh = new ArrayList<>();

        // ── 1. Regular apps ──────────────────────────────────────────────────
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon  = ri.loadIcon(pm);
            String   label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }

        // ── 2. TV Inputs (HDMI 1, HDMI 2, AV, Live TV) ─────────────────────
        // Xiaomi Mi TV / Redmi TV expose inputs through MiTVTVPlayer source ids.
        // Other Android TV boxes use the standard TvContract passthrough channels.
        boolean miPlayer = HdmiHelper.isMiPlayer(ctx);
        if (miPlayer) {
            int[] hdmi = ctx.getResources().getIntArray(R.array.source_hdmi);
            int av = ctx.getResources().getInteger(R.integer.source_av);

            for (int i = 0; i < hdmi.length; i++) {
                String title = "HDMI " + (i + 1);
                boolean blue = (i == 1);          // keep HDMI 2's blue accent
                fresh.add(new Entry(title, "hdmi_" + (i + 1),
                        createHdmiBanner(ctx, title, "Input", false,
                                blue ? 0xFF0D1B2A : 0xFF141414,
                                blue ? 0xFF1B263B : 0xFF222222),
                        getDrawableSafe(ctx, R.drawable.ic_input),
                        HdmiHelper.createMiSourceIntent(ctx, hdmi[i]),
                        blue ? 0xFF0077EE : 0xFF555555));
            }
            fresh.add(new Entry("AV Input", "av_input",
                    createHdmiBanner(ctx, "AV", "Composite", false, 0xFF141414, 0xFF222222),
                    getDrawableSafe(ctx, R.drawable.ic_av),
                    HdmiHelper.createMiSourceIntent(ctx, av), 0xFF555555));
            fresh.add(new Entry("Live TV", "live_tv",
                    createHdmiBanner(ctx, "TV", "Tuner", false, 0xFF1A1206, 0xFF2B1F08),
                    getDrawableSafe(ctx, R.drawable.ic_livetv),
                    HdmiHelper.createTvIntent(ctx), 0xFFCC8800));
        } else {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // Sort: HDMI first, then others
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI;
                boolean hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(ctx))
                             .compareToIgnoreCase(String.valueOf(b.loadLabel(ctx)));
            });

            int hdmiIdx = 1;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                boolean isHdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                int state = tim.getInputState(info.getId());
                boolean isConnected = (state == TvInputManager.INPUT_STATE_CONNECTED);

                Intent switchIntent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(info.getId()),
                                "vnd.android.cursor.item/channel")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                if (isHdmi) {
                    String pkgKey = "hdmi_" + hdmiIdx;
                    String title = "HDMI " + hdmiIdx;
                    String sub = isConnected ? "Connected" : "No Device";
                    int colour = (hdmiIdx == 2) ? 0xFF0077EE : 0xFF555555;
                    Drawable banner = (hdmiIdx == 2)
                            ? createHdmiBanner(ctx, title, sub, isConnected, 0xFF0D1B2A, 0xFF1B263B)
                            : createHdmiBanner(ctx, title, sub, isConnected, 0xFF141414, 0xFF222222);
                    fresh.add(new Entry(title, pkgKey, banner,
                            getDrawableSafe(ctx, R.drawable.ic_input), switchIntent, colour));
                    hdmiIdx++;
                } else {
                    fresh.add(new Entry("AV Input", "av_input",
                            createHdmiBanner(ctx, "AV", isConnected ? "Connected" : "Composite",
                                    isConnected, 0xFF141414, 0xFF222222),
                            getDrawableSafe(ctx, R.drawable.ic_av), switchIntent, 0xFF555555));
                }
            }
        }
        }

        // ── 2b. NLRC PenDrive Viewer (Offline Media Part 1, 2, 3) ──────────
        Intent penDriveIntent = new Intent(ctx, PenDriveActivity.class);
        penDriveIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Drawable fallbackPlayIcon;
        try {
            fallbackPlayIcon = ctx.getDrawable(android.R.drawable.ic_media_play);
        } catch (Exception ex) {
            fallbackPlayIcon = null;
        }
        Drawable mediaBanner = createMediaTileBanner(ctx);
        fresh.add(new Entry("NLRC PenDrive Viewer", "nlrc_pendrive",
                mediaBanner, fallbackPlayIcon, penDriveIntent, 0xFFFFB300));

        // ── 3. Dock: only the tiles the user pinned, in that order. Grid: apps only. ──────
        Map<String, Entry> byKey = new HashMap<>();
        for (Entry e : fresh) byKey.put(e.pkg, e);

        dockItems.clear();
        for (String key : dockStore.keys()) {
            if (dockItems.size() >= MAX_DOCK) break;
            Entry e = byKey.remove(key);
            if (e == null) continue;          // uninstalled app / port that no longer exists
            dockItems.add(e);
        }

        // Inputs (HDMI / AV / Live TV) live in the Inputs menu and optionally in the dock,
        // never in the app grid.
        List<Entry> rest = new ArrayList<>();
        for (Entry e : byKey.values()) if (!e.isInput) rest.add(e);
        Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));

        gridItems.clear();
        gridItems.addAll(rest);

        // ── 4. The list the RecyclerView sees: dock row, placeholder slots, then the apps ──
        items.clear();
        items.addAll(dockItems);
        // Pad the dock row with empty slots so the first app tile always starts a new row.
        if (!dockItems.isEmpty()) {
            while (items.size() < columns) items.add(null);
        }
        items.addAll(gridItems);
        // Two empty rows at the end. The list is one row taller than the visible window, and a
        // RecyclerView whose content is exactly as tall as its own viewport reports
        // canScrollVertically(false): with nothing to page into, the rows could never slide.
        for (int i = 0; i < columns * 2; i++) items.add(null);
    }

    /** Positions 0 .. realCount()-1 hold real tiles; the tail is the empty page-down room. */
    int realCount() {
        return Math.max(0, items.size() - columns * 2);
    }

    /**
     * Re-reads the pinned keys. Pinning moves a tile into the dock row, unpinning sends it back
     * to the app list, so the whole list has to be rebuilt.
     */
    void refresh() {
        reload();
        notifyDataSetChanged();
    }

    /** Number of pinned tiles; the dock row is the first {@code dockCount()} positions. */
    int dockCount() {
        return dockItems.size();
    }

    /** True when the position is part of the (visible) dock row, including its empty slots. */
    boolean isDockRow(int position) {
        return !dockItems.isEmpty() && position >= 0 && position < columns;
    }

    /** True when the position is a real pinned tile of the dock row. */
    boolean isDockPosition(int position) {
        return position >= 0 && position < dockItems.size();
    }

    /** Adapter position of a tile key, or -1. */
    int positionOfKey(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            Entry e = items.get(i);
            if (e != null && key.equals(e.pkg)) return i;
        }
        return -1;
    }

    /** Label of a tile by key (used for the confirmation toast). */
    String pinnedLabel(String key) {
        for (Entry e : dockItems) if (e.pkg.equals(key)) return e.label;
        for (Entry e : gridItems) if (e.pkg.equals(key)) return e.label;
        return key;
    }

    /** Key ("pkg" or input id) of the entry behind a position, or null for an empty dock slot. */
    String keyAt(int position) {
        Entry e = entryAt(position);
        return e == null ? null : e.pkg;
    }

    /** Looks a tile up by key across the dock and the app grid. */
    private Entry entryForKey(String key) {
        if (key == null) return null;
        for (Entry e : dockItems) if (key.equals(e.pkg)) return e;
        for (Entry e : gridItems) if (key.equals(e.pkg)) return e;
        return null;
    }

    /**
     * Package name of a tile that can be uninstalled, or null for inputs, built-in tiles,
     * this launcher itself and non-removable system apps.
     */
    String uninstallablePackage(String key) {
        Entry e = entryForKey(key);
        if (e == null || e.isInput || e.component == null) return null;
        String pkg = e.pkg;
        if (pkg == null || pkg.equals(ctx.getPackageName())) return null;
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            // Preinstalled apps cannot be removed; updated system apps can only drop their updates.
            if (system && !updatedSystem) return null;
        } catch (PackageManager.NameNotFoundException ex) {
            return null;
        }
        return pkg;
    }


    /**
     * Creates a sleek, high-definition rectangular banner for HDMI inputs matching
     * the visual weight of Netflix and JioHotstar app cards.
     */
    private static Drawable createHdmiBanner(Context ctx, String mainTitle, String subTitle, boolean isConnected, int colStart, int colEnd) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = (int) (180 * d);
        int h = (int) (101 * d);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // 1. Rich dark gradient card background
        Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBg.setShader(new LinearGradient(0, 0, w, h, colStart, colEnd, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, pBg);

        // 2. Translucent top specular sheen
        Paint pSpec = new Paint(Paint.ANTI_ALIAS_FLAG);
        pSpec.setShader(new LinearGradient(0, 0, 0, h * 0.55f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.55f, pSpec);

        // 3. HDMI / TV Hardware Vector Icon in top-left
        try {
            Drawable ic = ctx.getDrawable(R.drawable.ic_input);
            if (ic != null) {
                int icSize = (int) (24 * d);
                int icX    = (int) (16 * d);
                int icY    = (int) (16 * d);
                ic.setBounds(icX, icY, icX + icSize, icY + icSize);
                ic.setTint(0xE6FFFFFF);
                ic.draw(c);
            }
        } catch (Exception ignored) {}

        // 4. Status Dot in top-right (Green = Connected, Dim Gray = Standby)
        Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        pDot.setColor(isConnected ? 0xFF10B981 : 0x55FFFFFF);
        c.drawCircle(w - (18 * d), 22 * d, 4f * d, pDot);

        // 5. Main Title ("HDMI 1")
        Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
        pTitle.setColor(Color.WHITE);
        pTitle.setTextSize(17f * d);
        pTitle.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pTitle.setShadowLayer(4f * d, 0, 2f * d, 0xAA000000);
        c.drawText(mainTitle, 16 * d, h - (32 * d), pTitle);

        // 6. Subtitle ("Jio TV")
        Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
        pSub.setColor(0xFFCBD5E1); // Clean silver
        pSub.setTextSize(12f * d);
        pSub.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText(subTitle, 16 * d, h - (14 * d), pSub);

        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    /** getDrawable that never throws on old firmwares. */
    private static Drawable getDrawableSafe(Context ctx, int resId) {
        try {
            return ctx.getDrawable(resId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Creates a sleek card banner for PenDrive / Local Media Viewer.
     */
    private static Drawable createMediaTileBanner(Context ctx) {
        try {
            Drawable customBanner = ctx.getDrawable(R.drawable.banner_pendrive_viewer);
            if (customBanner != null) return customBanner;
        } catch (Exception ignored) {}

        float d = ctx.getResources().getDisplayMetrics().density;
        int w = (int) (180 * d);
        int h = (int) (101 * d);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // 1. Draw base dark gradient card background
        Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBg.setShader(new LinearGradient(0, 0, w, h, 0xFF2A1B08, 0xFF0E1626, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, pBg);

        // 2. Heavy dark gradient scrim on bottom for readability
        Paint pScrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        pScrim.setShader(new LinearGradient(0, h * 0.35f, 0, h, 0x00000000, 0xF0000000, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.35f, w, h, pScrim);

        // 3. Gold Pill Badge in Top-Right
        Paint pBadge = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBadge.setColor(0xEEB8860B);
        RectF rBadge = new RectF(w - (68 * d), 10 * d, w - (12 * d), 26 * d);
        c.drawRoundRect(rBadge, 8 * d, 8 * d, pBadge);

        Paint pBadgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBadgeText.setColor(Color.WHITE);
        pBadgeText.setTextSize(9f * d);
        pBadgeText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("3 部", w - (62 * d), 21 * d, pBadgeText);

        // 4. Main Title
        Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
        pTitle.setColor(Color.WHITE);
        pTitle.setTextSize(15f * d);
        pTitle.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pTitle.setShadowLayer(4f * d, 0, 2f * d, 0xDD000000);
        c.drawText("PenDrive Viewer", 14 * d, h - (26 * d), pTitle);

        // 5. Subtitle
        Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
        pSub.setColor(0xFFFFD700); // Bright gold
        pSub.setTextSize(11f * d);
        pSub.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pSub.setShadowLayer(3f * d, 0, 1f * d, 0xDD000000);
        c.drawText("Personal Videos · Part 1, 2, 3", 14 * d, h - (10 * d), pSub);

        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        Entry e = entryAt(position);
        if (e == null) {
            // Empty slot that keeps the dock row as wide as the grid.
            h.itemView.setVisibility(View.INVISIBLE);
            h.card.setFocusable(false);
            h.card.setClickable(false);
            h.card.setOnClickListener(null);
            h.card.setOnLongClickListener(null);
            return;
        }
        h.itemView.setVisibility(View.VISIBLE);
        h.card.setFocusable(true);
        h.card.setClickable(true);

        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }

        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            return pos != RecyclerView.NO_POSITION && listener != null
                    && listener.onTileLongPress(pos, h.card);
        });
    }

    private void launch(Entry e) {
        try {
            if ("com.netflix.ninja".equals(e.pkg) || "com.amazon.amazonvideo.livingroom".equals(e.pkg)) {
                // User requirement: Clicking Netflix or Prime Video opens HDMI 2 (Jio TV)
                HdmiHelper.switchToHdmi2(ctx, true);
                return;
            }
            if (e.launchIntent != null) {
                // Switch directly to HDMI 2 (Jio TV) or selected input
                ctx.startActivity(e.launchIntent);
            } else {
                // Regular app launch
                Intent i = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(e.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                ctx.startActivity(i);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private Entry entryAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    String labelAt(int position) {
        Entry e = entryAt(position);
        return e == null ? "" : e.label;
    }

    int ambientColorAt(int position) {
        Entry e = entryAt(position);
        if (e == null) return 0xFF444444;
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        Drawable d = live.getConstantState() != null
                ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);
                if (score > bestScore) { bestScore = score; best = p; }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {
            int avg = Color.rgb((int)(r / (n * n)), (int)(g / (n * n)), (int)(b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View      card;
        final ImageView banner;
        final ImageView icon;
        final TextView  label;

        Holder(View v, TileListener listener) {
            super(v);
            card   = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon   = v.findViewById(R.id.icon);
            label  = v.findViewById(R.id.label);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius  = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return false;
                }
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION
                        && listener.onTileKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
