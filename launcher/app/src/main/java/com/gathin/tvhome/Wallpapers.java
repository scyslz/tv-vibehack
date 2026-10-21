package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rotating wallpaper.
 *
 * With a network connection the launcher shows Bing's daily wallpaper (the same editorial set
 * Windows lock screen uses): seasonal, location-themed photos picked per market. The image list is
 * refreshed at most every 30 minutes and a new photo is shown every 3 minutes. Network photos are
 * kept in RAM only - nothing is written to the TV's storage, so the launcher can never fill the
 * flash with wallpapers and the current picture disappears when the app process ends.
 *
 * Without a network (or while the first download is still running) the bundled set is used, so the
 * home screen always has a wallpaper. The rotation slot is still 3 minutes.
 *
 * Users can supply their own photos without rebuilding: drop JPEG/PNG files into
 *     /sdcard/Android/data/com.gathin.tvhome/files/wallpapers
 * and they take precedence over everything else. Keeping the folder empty falls back to the
 * network / bundled photos.
 */
final class Wallpapers {

    private static final String TAG = "Wallpapers";
    private static final String USER_DIR = "wallpapers";

    /** How often the photo changes, for both network and bundled wallpapers. */
    private static final long SLOT_MS = 3 * 60 * 1000L;

    /** How long the Bing image list stays valid before it is fetched again. */
    private static final long NET_LIST_TTL = 30 * 60 * 1000L;

    /** Network photos kept in memory. Old ones are dropped (not recycled) so the view never dies. */
    private static final int NET_CACHE_MAX = 3;

    private static final String[] BUNDLED = {
            "wallpapers/01_norway_fjord.jpg",
            "wallpapers/02_canyon_sunset.jpg",
            "wallpapers/03_alpine_lake.jpg",
            "wallpapers/04_redwood_trail.jpg",
            "wallpapers/05_snow_peaks.jpg",
            "wallpapers/06_sea_pier.jpg",
            "wallpapers/07_golden_field.jpg",
            "wallpapers/08_desert_dunes.jpg",
            "wallpapers/09_coastline.jpg",
            "wallpapers/10_night_ridge.jpg",
    };

    /** Identifies the image currently on screen, so a redraw is only done when the slot changes. */
    private static String currentKey;

    /** Keeps a weak handle so a download that finishes later can still paint the launcher. */
    private static WeakReference<ImageView> targetView;
    private static Context appContext;

    private static final List<String> netUrls = new ArrayList<>();
    private static long netListAt;
    private static boolean netListLoading;
    private static String netPendingUrl;
    private static final LinkedHashMap<String, Bitmap> netCache =
            new LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > NET_CACHE_MAX;
                }
            };

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Wallpapers() {}

    /**
     * Picks the wallpaper for the current slot and puts it on the view.
     * Returns true when the image actually changed.
     */
    static boolean apply(Context ctx, ImageView view, boolean animate) {
        if (view == null) return false;
        appContext = ctx.getApplicationContext();
        targetView = new WeakReference<>(view);

        // 1. The user's own photos always win.
        List<String> userFiles = userWallpapers(ctx);
        if (!userFiles.isEmpty()) {
            long slot = System.currentTimeMillis() / SLOT_MS;
            String path = userFiles.get((int) (Math.abs(slot) % userFiles.size()));
            String key = "file:" + path;
            if (key.equals(currentKey) && view.getDrawable() != null) return false;
            Bitmap bitmap = decodeFile(path, ctx);
            if (bitmap != null) return show(view, bitmap, key, animate);
        }

        // 2. Online: Bing's daily photo (memory only). While it downloads, keep showing something.
        if (isOnline(ctx)) {
            String url = currentNetUrl();
            if (url != null) {
                Bitmap cached = netCache.get(url);
                if (cached != null) return show(view, cached, "net:" + url, animate);
                requestNetImage(url);
            }
        }

        // 3. Offline / first launch: the bundled set.
        return showBundled(ctx, view, animate);
    }

    private static boolean showBundled(Context ctx, ImageView view, boolean animate) {
        long slot = System.currentTimeMillis() / SLOT_MS;
        String path = BUNDLED[(int) (Math.abs(slot) % BUNDLED.length)];
        String key = "asset:" + path;
        if (key.equals(currentKey) && view.getDrawable() != null) return false;
        Bitmap bitmap = decodeAsset(ctx, path);
        if (bitmap == null) return false;
        return show(view, bitmap, key, animate);
    }

    private static boolean show(ImageView view, Bitmap bitmap, String key, boolean animate) {
        if (key.equals(currentKey) && view.getDrawable() != null) return false;
        currentKey = key;
        view.setImageBitmap(bitmap);
        if (animate) {
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(900).start();
        } else {
            view.setAlpha(1f);
        }
        return true;
    }

    /** Forgets the cached choice, e.g. when the user adds or removes photos. */
    static void invalidate() {
        currentKey = null;
    }

    // ---- network wallpapers ----------------------------------------------------------------------

    /** The Bing photo that belongs to the current slot, or null while the list is still loading. */
    private static String currentNetUrl() {
        long now = System.currentTimeMillis();
        if (netUrls.isEmpty() || now - netListAt > NET_LIST_TTL) requestNetList();
        if (netUrls.isEmpty()) return null;
        return netUrls.get((int) ((now / SLOT_MS) % netUrls.size()));
    }

    /** Same lookup without triggering work, for use from the loader callback. */
    private static String currentNetUrlQuiet() {
        if (netUrls.isEmpty()) return null;
        long now = System.currentTimeMillis();
        return netUrls.get((int) ((now / SLOT_MS) % netUrls.size()));
    }

    private static void requestNetList() {
        if (netListLoading || appContext == null) return;
        netListLoading = true;
        final Context ctx = appContext;
        IO.execute(() -> {
            final List<String> urls = fetchBingUrls(ctx);
            MAIN.post(() -> {
                netListLoading = false;
                // Even on failure, wait a full TTL before hammering the network again.
                netListAt = System.currentTimeMillis();
                if (urls.isEmpty()) return;
                netUrls.clear();
                netUrls.addAll(urls);
                // Paint the first photo right away instead of waiting for the next minute tick.
                ImageView view = targetView != null ? targetView.get() : null;
                if (view != null) apply(ctx, view, true);
            });
        });
    }

    private static void requestNetImage(final String url) {
        if (url.equals(netPendingUrl) || appContext == null) return;
        netPendingUrl = url;
        final Context ctx = appContext;
        IO.execute(() -> {
            final Bitmap bitmap = downloadBitmap(url, ctx);
            MAIN.post(() -> {
                netPendingUrl = null;
                if (bitmap == null) return;
                netCache.put(url, bitmap);
                // The slot may have moved on while downloading; only paint the current photo.
                if (!url.equals(currentNetUrlQuiet())) return;
                ImageView view = targetView != null ? targetView.get() : null;
                if (view != null) show(view, bitmap, "net:" + url, true);
            });
        });
    }

    /**
     * Bing's HPImageArchive is the feed behind the Windows lock screen wallpapers. It returns one
     * editorial photo per day, already themed for the market's season and region.
     */
    private static List<String> fetchBingUrls(Context ctx) {
        List<String> urls = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://www.bing.com/HPImageArchive.aspx?format=js&n=8&pid=hp&mkt=" + market());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) NLRC-Home");
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Bing list HTTP " + code);
                return urls;
            }
            InputStream in = conn.getInputStream();
            String json = new String(readAll(in), "UTF-8");
            in.close();
            JSONArray images = new JSONObject(json).optJSONArray("images");
            if (images == null) return urls;
            for (int i = 0; i < images.length(); i++) {
                String base = images.getJSONObject(i).optString("urlbase", "");
                if (base.isEmpty()) continue;
                urls.add("https://www.bing.com" + base + "_1920x1080.jpg");
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot load Bing wallpapers: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return urls;
    }

    private static Bitmap downloadBitmap(String url, Context ctx) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) NLRC-Home");
            // Never let the HTTP stack keep a copy on disk.
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Image HTTP " + code);
                return null;
            }
            InputStream in = conn.getInputStream();
            Bitmap bitmap = decode(in, ctx);
            in.close();
            return bitmap;
        } catch (Exception e) {
            Log.w(TAG, "Cannot download wallpaper: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Bing market code from the TV's language, so photos fit the region. */
    private static String market() {
        String lang = Locale.getDefault().getLanguage();
        if (lang.startsWith("zh")) return "zh-CN";
        if (lang.startsWith("ja")) return "ja-JP";
        if (lang.startsWith("ko")) return "ko-KR";
        if (lang.startsWith("de")) return "de-DE";
        if (lang.startsWith("fr")) return "fr-FR";
        if (lang.startsWith("es")) return "es-ES";
        return "en-US";
    }

    private static boolean isOnline(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }

    /** The user's own photos, sorted so the rotation order is stable. */
    private static List<String> userWallpapers(Context ctx) {
        List<String> files = new ArrayList<>();
        try {
            File dir = ctx.getExternalFilesDir(USER_DIR);
            if (dir == null) {
                dir = new File(Environment.getExternalStorageDirectory(),
                        "Android/data/" + ctx.getPackageName() + "/files/" + USER_DIR);
            }
            if (!dir.exists()) dir.mkdirs();
            File[] list = dir.listFiles();
            if (list == null) return files;
            Arrays.sort(list);
            for (File f : list) {
                String name = f.getName().toLowerCase();
                if (f.isFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))) {
                    files.add(f.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot list user wallpapers: " + e.getMessage());
        }
        return files;
    }

    private static Bitmap decodeAsset(Context ctx, String path) {
        try {
            InputStream in = ctx.getAssets().open(path);
            Bitmap bmp = decode(in, ctx);
            in.close();
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "Cannot open " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static Bitmap decodeFile(String path, Context ctx) {
        try {
            InputStream in = new FileInputStream(path);
            Bitmap bmp = decode(in, ctx);
            in.close();
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "Cannot open " + path + ": " + e.getMessage());
            return null;
        }
    }

    /** Decodes at roughly screen resolution so a 8K photo cannot exhaust this old TV's heap. */
    private static Bitmap decode(InputStream in, Context ctx) {
        try {
            byte[] data = readAll(in);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= dm.widthPixels
                    && bounds.outHeight / (sample * 2) >= dm.heightPixels) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            Log.w(TAG, "Cannot decode wallpaper: " + e.getMessage());
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Milliseconds until the next rotation slot starts, so the caller can schedule a refresh. */
    static long millisUntilNextSlot() {
        long now = System.currentTimeMillis();
        return SLOT_MS - (now % SLOT_MS);
    }

    /** True when the calendar has moved into a different slot since the given timestamp. */
    static boolean slotChangedSince(long timestamp) {
        return timestamp / SLOT_MS != System.currentTimeMillis() / SLOT_MS;
    }
}
