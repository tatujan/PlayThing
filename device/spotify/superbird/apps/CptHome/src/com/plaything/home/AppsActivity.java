/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The app drawer (docs/home-screen.md, "Apps"): each app with a launcher entry, from the image,
 * the store, or another source, as a grid of tiles. The knob moves the focus one tile at a time
 * (DPAD left and right), the knob press opens the app, and Back goes back to the home screen.
 * Touch also works. The icons are in memory only while the drawer is visible.
 *
 * All sizes are pixels, as in HomeView (density 240).
 */
public class AppsActivity extends Activity {
    private static final String TAG = "CptHome";
    private static final long KNOB_GAP_MS = 180;

    // Layout: as many columns of at least 128 px as fit (5 of 137 px in landscape, 3 of 133 px
    // in portrait), 16 px apart.
    private static final int MARGIN = 24;
    private static final int MIN_TILE_W = 128;
    private static final int TILE_H = 128;
    private static final int GAP = 16;
    private static final int ICON = 64;
    private static final float RADIUS = 14;

    // Colors (HomeView).
    private static final int TEXT = 0xFFE9E9ED;
    private static final int TEXT_MUTED = 0xFF9397AB;
    private static final int ACCENT = 0xFF34C759;
    private static final int TILE_FILL = 0x8C232532;
    private static final int TILE_FILL_FOCUS = 0x1F34C759;
    private static final int TILE_FILL_PRESSED = 0x3334C759;
    private static final int TILE_BORDER = 0x1AE9E9ED;

    private static final class App {
        ComponentName component;
        String category;
        String label;
    }

    private final List<App> mApps = new ArrayList<>();
    private final List<View> mTiles = new ArrayList<>();
    private ScrollView mScroll;
    private LinearLayout mGrid;
    private Typeface mMedium;
    private int mColumns;
    private int mTileW;
    private int mFocus;
    private String mFocusPkg;
    private boolean mEnterDown;
    private long mLastKnob = -KNOB_GAP_MS;

    private final BroadcastReceiver mPackages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            rebuild();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMedium = Typeface.create(Typeface.SANS_SERIF, 500, false);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.apps);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
        title.setTextColor(TEXT);
        title.setTypeface(mMedium);
        title.setIncludeFontPadding(false);
        title.setPadding(MARGIN, 18, MARGIN, 14);
        screen.addView(title);
        mScroll = new ScrollView(this);
        mScroll.setFocusable(false);
        mScroll.setDefaultFocusHighlightEnabled(false);
        mGrid = new LinearLayout(this);
        mGrid.setOrientation(LinearLayout.VERTICAL);
        mGrid.setPadding(MARGIN, 4, MARGIN, MARGIN);
        mScroll.addView(mGrid);
        screen.addView(mScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(screen);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(mPackages, filter);
        rebuild();
    }

    // The rotate button of the home screen changes the orientation (configChanges).
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (!mTiles.isEmpty()) {
            rebuild();
        }
    }

    @Override
    protected void onStop() {
        unregisterReceiver(mPackages);
        // Release the icons while an app is in front.
        mGrid.removeAllViews();
        mTiles.clear();
        mEnterDown = false;
        super.onStop();
    }

    /** The apps with a launcher entry (also TV apps), one entry for each package, by name. */
    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, App> apps = new LinkedHashMap<>();
        for (String category : new String[] {Intent.CATEGORY_LAUNCHER,
                Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || apps.containsKey(pkg)) {
                    continue;
                }
                App a = new App();
                a.component = new ComponentName(pkg, ri.activityInfo.name);
                a.category = category;
                a.label = ri.loadLabel(pm).toString();
                apps.put(pkg, a);
            }
        }
        mApps.clear();
        mApps.addAll(apps.values());
        mApps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
    }

    private void rebuild() {
        loadApps();
        int avail = getResources().getDisplayMetrics().widthPixels - 2 * MARGIN;
        mColumns = Math.max(2, (avail + GAP) / (MIN_TILE_W + GAP));
        mTileW = (avail - (mColumns - 1) * GAP) / mColumns;
        PackageManager pm = getPackageManager();
        mGrid.removeAllViews();
        mTiles.clear();
        LinearLayout row = null;
        for (int i = 0; i < mApps.size(); i++) {
            if (i % mColumns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, TILE_H);
                lp.bottomMargin = GAP;
                mGrid.addView(row, lp);
            }
            View tile = tile(mApps.get(i), pm);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(mTileW, TILE_H);
            if (i % mColumns != mColumns - 1) {
                lp.rightMargin = GAP;
            }
            row.addView(tile, lp);
            mTiles.add(tile);
        }
        int keep = 0;
        for (int i = 0; i < mApps.size(); i++) {
            if (mApps.get(i).component.getPackageName().equals(mFocusPkg)) {
                keep = i;
            }
        }
        mFocus = -1;
        setFocus(keep);
    }

    private View tile(App app, PackageManager pm) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(8, 0, 8, 0);
        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(pm.getActivityIcon(app.component));
        } catch (PackageManager.NameNotFoundException e) {
            icon.setImageDrawable(pm.getDefaultActivityIcon());
        }
        tile.addView(icon, new LinearLayout.LayoutParams(ICON, ICON));
        TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15);
        label.setTextColor(TEXT_MUTED);
        label.setTypeface(mMedium);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 10;
        tile.addView(label, lp);
        tile.setOnClickListener(v -> {
            int index = mTiles.indexOf(v);
            if (index >= 0) {
                setFocus(index);
                open(mApps.get(index));
            }
        });
        style(tile, false, false);
        return tile;
    }

    private void open(App app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(app.category)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "cannot open " + app.component, e);
        }
    }

    private void setFocus(int index) {
        if (mTiles.isEmpty()) {
            return;
        }
        int i = Math.max(0, Math.min(mTiles.size() - 1, index));
        mEnterDown = false;
        if (mFocus >= 0 && mFocus < mTiles.size()) {
            style(mTiles.get(mFocus), false, false);
        }
        mFocus = i;
        mFocusPkg = mApps.get(i).component.getPackageName();
        style(mTiles.get(i), true, false);
        reveal(i / mColumns);
    }

    /** Scrolls the grid so that the row of the focused tile is visible. */
    private void reveal(int rowIndex) {
        mScroll.post(() -> {
            View row = mGrid.getChildAt(rowIndex);
            if (row == null) {
                return;
            }
            int top = rowIndex == 0 ? 0 : row.getTop() - GAP;
            int bottom = row.getBottom() + GAP;
            int y = mScroll.getScrollY();
            int h = mScroll.getHeight();
            if (top < y) {
                mScroll.smoothScrollTo(0, top);
            } else if (bottom > y + h) {
                mScroll.smoothScrollTo(0, bottom - h);
            }
        });
    }

    private void style(View v, boolean focus, boolean pressed) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(RADIUS);
        d.setColor(focus ? (pressed ? TILE_FILL_PRESSED : TILE_FILL_FOCUS) : TILE_FILL);
        d.setStroke(1, focus ? ACCENT : TILE_BORDER);
        v.setBackground(d);
        View label = ((ViewGroup) v).getChildAt(1);
        if (label instanceof TextView) {
            ((TextView) label).setTextColor(focus ? TEXT : TEXT_MUTED);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    knob(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, event.getEventTime());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // A keyboard: one row up or down.
                if (down) {
                    setFocus(mFocus + (code == KeyEvent.KEYCODE_DPAD_DOWN ? mColumns : -mColumns));
                }
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (mFocus < 0 || mFocus >= mTiles.size()) {
                    return true;
                }
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        mEnterDown = true;
                        style(mTiles.get(mFocus), true, true);
                    }
                } else if (mEnterDown) {
                    mEnterDown = false;
                    style(mTiles.get(mFocus), true, false);
                    open(mApps.get(mFocus));
                }
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // Some input drivers send the knob as scroll events.
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            float v = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (v == 0) {
                v = -event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            }
            if (v != 0) {
                knob(v > 0 ? 1 : -1, event.getEventTime());
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /** Clockwise (+1) moves the focus to the next tile (right, then the next row). No wrap. */
    private void knob(int step, long time) {
        if (time - mLastKnob < KNOB_GAP_MS) {
            return;
        }
        mLastKnob = time;
        setFocus(mFocus + step);
    }
}
