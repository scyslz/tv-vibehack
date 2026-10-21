package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * NLRC PenDrive / Local Media Viewer:
 * A parent-friendly interface presenting personal family media archives
 * in 3 easily selectable parts (Part 1, Part 2, Part 3) with TV remote navigation.
 */
public class PenDriveActivity extends Activity {

    private static final String TAG = "PenDriveActivity";

    private FrameLayout cardPart1, cardPart2, cardPart3;
    private TextView tvUsbStatus;
    private TextView tvDuration1, tvDuration2, tvDuration3;

    private static final String PART1_FILENAME = "Part_1.mp4";
    private static final String PART2_FILENAME = "Part_2.mp4";
    private static final String PART3_FILENAME = "Part_3.mp4";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pendrive_viewer);
        Wallpapers.apply(this, (android.widget.ImageView) findViewById(R.id.bgWallpaper), false);

        cardPart1 = findViewById(R.id.cardPart1);
        cardPart2 = findViewById(R.id.cardPart2);
        cardPart3 = findViewById(R.id.cardPart3);
        tvUsbStatus = findViewById(R.id.tvUsbStatus);
        tvDuration1 = findViewById(R.id.tvDuration1);
        tvDuration2 = findViewById(R.id.tvDuration2);
        tvDuration3 = findViewById(R.id.tvDuration3);

        setupCard(cardPart1, 1);
        setupCard(cardPart2, 2);
        setupCard(cardPart3, 3);

        checkStorageStatus();

        // Focus first card on start
        cardPart1.post(() -> cardPart1.requestFocus());
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkStorageStatus();
    }

    private void setupCard(View card, int partNumber) {
        card.setOnClickListener(v -> openVideoPlayer(partNumber));

        card.setOnFocusChangeListener((v, hasFocus) -> {
            float scale = hasFocus ? 1.08f : 1.0f;
            v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(hasFocus ? 16f : 0f)
                    .setDuration(200)
                    .start();
        });
    }

    private void openVideoPlayer(int partNumber) {
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra("PART_NUMBER", partNumber);
        startActivity(intent);
    }

    private void checkStorageStatus() {
        int foundCount = 0;
        if (findPartFile(1) != null) foundCount++;
        if (findPartFile(2) != null) foundCount++;
        if (findPartFile(3) != null) foundCount++;

        if (foundCount >= 3) {
            tvUsbStatus.setText("TV Storage: 3 Videos Ready");
            tvUsbStatus.setTextColor(0xFF10B981); // Emerald green
        } else if (foundCount > 0) {
            tvUsbStatus.setText("TV Storage: " + foundCount + " Videos Ready");
            tvUsbStatus.setTextColor(0xFF10B981);
        } else {
            tvUsbStatus.setText("Storage: Waiting for Videos");
            tvUsbStatus.setTextColor(0xFFFBBF24); // Warm amber
        }
    }

    public static File findPartFile(int part) {
        List<File> locations = getPotentialStorageLocations();
        for (File dir : locations) {
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".mp4"));
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName().toLowerCase();
                if (n.contains("part_" + part) || n.contains("part" + part) || n.contains("p" + part) || n.contains("_" + part + ".")) {
                    return f;
                }
            }
        }
        return null;
    }

    public static List<File> getPotentialStorageLocations() {
        List<File> dirs = new ArrayList<>();

        // 0. Primary TV Internal Storage dedicated NLRC directory
        dirs.add(new File("/sdcard/Movies/NLRC"));
        dirs.add(new File("/storage/emulated/0/Movies/NLRC"));
        dirs.add(new File("/sdcard/Movies"));

        // 1. Check all mounted external storage dirs in /storage
        File storageRoot = new File("/storage");
        if (storageRoot.exists() && storageRoot.isDirectory()) {
            File[] subs = storageRoot.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (sub.isDirectory() && !sub.getName().equals("self") && !sub.getName().equals("emulated")) {
                        dirs.add(sub);
                        dirs.add(new File(sub, "Movies"));
                    }
                }
            }
        }

        // 2. Standard internal /sdcard directories
        dirs.add(Environment.getExternalStorageDirectory());
        dirs.add(new File(Environment.getExternalStorageDirectory(), "Movies"));
        dirs.add(new File(Environment.getExternalStorageDirectory(), "Download"));
        dirs.add(new File("/sdcard"));

        return dirs;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
