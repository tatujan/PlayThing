/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.manager;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PlayThing App manager (docs/apps.md): the installed apps with their storage and memory, Clean
 * up (the caches of all apps), and for each app Open, Stop, Clear cache, Clear data, and Remove.
 * Stop, Clear data, and Remove need a second press. The knob moves the focus (DPAD left and
 * right), the knob press opens or runs the focused item (ENTER), and Back goes back; touch also
 * works.
 *
 * All sizes are pixels, as in CptHome: the display density is 240, so dp units would make
 * everything 1.5 times larger.
 */
public class ManagerActivity extends Activity {
    private static final String TAG = "CptManager";
    // One knob detent moves the focus by one item, as on the home screen.
    private static final long KNOB_GAP_MS = 180;
    // The key of the Clean up row.
    private static final String CLEANUP = "#cleanup";

    // Layout.
    private static final int MARGIN = 24;
    private static final int DETAIL_MARGIN = 32;
    private static final int ROW_H = 72;
    private static final int ROW_GAP = 8;
    private static final int ROW_PAD = 20;
    private static final float RADIUS = 12;
    private static final int BUTTON_H = 52;
    private static final int BUTTON_GAP = 12;
    private static final int BUTTON_PAD = 20;

    // Colors (CptHome, docs/home-screen.md), and an amber for warnings.
    private static final int BG = 0xFF161826;
    private static final int TEXT = 0xFFE9E9ED;
    private static final int TEXT_MUTED = 0xFF9397AB;
    private static final int ACCENT = 0xFF34C759;
    private static final int ACCENT_LIGHT = 0xFFB4F0C3;
    private static final int WARN = 0xFFFFC36B;
    private static final int FILL = 0x8C232532;
    private static final int FILL_FOCUS = 0x1F34C759;
    private static final int FILL_PRESSED = 0x3334C759;
    private static final int BORDER = 0x1AE9E9ED;

    private final ExecutorService mLoader = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<View> mRows = new ArrayList<>();
    private final List<String> mRowKeys = new ArrayList<>();
    private final List<View> mButtonViews = new ArrayList<>();
    private final List<Runnable> mButtonActions = new ArrayList<>();
    private List<View> mItems = mRows;

    private Jobs mJobs;
    private List<Apps.App> mApps = new ArrayList<>();
    private Map<String, Apps.Sizes> mSizes;
    private int mLoadId;
    private String mCleanupText;
    private String mListNote;

    private Typeface mMedium;
    private Typeface mRegular;
    private View mListScreen;
    private TextView mStatus;
    private ScrollView mScroll;
    private LinearLayout mList;
    private View mDetailScreen;
    private TextView mName;
    private TextView mInfo;
    private TextView mMessage;
    private HorizontalScrollView mButtonScroll;
    private LinearLayout mButtons;

    private int mFocus;
    private boolean mEnterDown;
    private long mLastKnob = -KNOB_GAP_MS;
    // The app on the app screen, or null for the list screen.
    private String mDetail;
    private String mListFocus;
    // The button that waits for its second press (its label resource), or 0.
    private int mConfirm;
    private String mNote;
    private boolean mNoteWarn;

    private final BroadcastReceiver mPackages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMedium = Typeface.create(Typeface.SANS_SERIF, 500, false);
        mRegular = Typeface.create(Typeface.SANS_SERIF, 400, false);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        mListScreen = buildList();
        mDetailScreen = buildDetail();
        mDetailScreen.setVisibility(View.GONE);
        root.addView(mListScreen);
        root.addView(mDetailScreen);
        setContentView(root);
        mJobs = new Jobs(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        filter.addDataScheme("package");
        registerReceiver(mPackages, filter);
        mCleanupText = null;
        reload();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(mPackages);
        mEnterDown = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mLoader.shutdownNow();
        mJobs.close();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mDetail != null) {
            showList();
        } else {
            finish();
        }
    }

    // ------------------------------------------------------------------ loading

    /** Loads the list, then measures the storage and the memory (worker thread). */
    private void reload() {
        int id = ++mLoadId;
        mLoader.execute(() -> {
            List<Apps.App> apps = Apps.load(this);
            mMain.post(() -> loaded(id, apps, null, null));
            Map<String, Long> memory = Apps.measureMemory(this);
            Map<String, Apps.Sizes> sizes = Apps.measureSizes(this);
            mMain.post(() -> loaded(id, apps, sizes, memory));
        });
    }

    private void loaded(int id, List<Apps.App> apps, Map<String, Apps.Sizes> sizes,
            Map<String, Long> memory) {
        if (id != mLoadId || isDestroyed()) {
            return;
        }
        if (sizes != null) {
            Apps.apply(apps, sizes, memory);
            mSizes = sizes;
        }
        mApps = apps;
        rebuild();
    }

    private Apps.App find(String pkg) {
        for (Apps.App a : mApps) {
            if (a.pkg.equals(pkg)) {
                return a;
            }
        }
        return null;
    }

    private String size(long bytes) {
        return Formatter.formatShortFileSize(this, Math.max(0, bytes));
    }

    // ------------------------------------------------------------------ the list

    private View buildList() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(MARGIN, 18, MARGIN, 12);
        header.addView(text(getString(R.string.app_name), 26, TEXT, mMedium));
        mStatus = text("", 15, TEXT_MUTED, mRegular);
        mStatus.setPadding(0, 6, 0, 0);
        header.addView(mStatus);
        screen.addView(header);
        mScroll = new ScrollView(this);
        mScroll.setFocusable(false);
        mScroll.setDefaultFocusHighlightEnabled(false);
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(MARGIN, 4, MARGIN, MARGIN);
        mScroll.addView(mList);
        screen.addView(mScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return screen;
    }

    private void updateStatus() {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mi);
        mStatus.setText(getString(R.string.status, size(fs.getAvailableBytes()),
                size(fs.getTotalBytes()), size(mi.availMem)));
    }

    /** The cache bytes of all packages, or -1 before the measurement. */
    private long cacheTotal() {
        if (mSizes == null) {
            return -1;
        }
        long total = 0;
        for (Apps.Sizes z : mSizes.values()) {
            total += z.cache;
        }
        return total;
    }

    /** Makes the rows again (after a load or a job), and keeps the focus. */
    private void rebuild() {
        updateStatus();
        mList.removeAllViews();
        mRows.clear();
        mRowKeys.clear();
        long cache = cacheTotal();
        String sub = mCleanupText != null ? mCleanupText
                : cache < 0 ? getString(R.string.cleanup_measuring)
                : getString(R.string.cleanup_size, size(cache));
        row(CLEANUP, getString(R.string.cleanup), sub, mListNote != null ? mListNote : "",
                mListNote != null ? ACCENT_LIGHT : TEXT_MUTED);
        // Installed apps first, then the apps of the image, each with a title.
        for (int pass = 0; pass < 2; pass++) {
            boolean title = false;
            for (Apps.App a : mApps) {
                boolean fromImage = a.system && !a.updated;
                if (fromImage != (pass == 1)) {
                    continue;
                }
                if (!title) {
                    section(getString(pass == 0 ? R.string.section_installed
                            : R.string.section_system));
                    title = true;
                }
                String version = a.versionName != null ? a.versionName : "";
                String right = a.pssKb > 0 ? getString(R.string.row_running, size(a.pssKb * 1024))
                        : a.totalBytes() >= 0 ? getString(R.string.row_size, size(a.totalBytes()))
                        : "";
                row(a.pkg, a.label, version, right, a.pssKb > 0 ? ACCENT_LIGHT : TEXT_MUTED);
            }
        }
        if (mDetail != null) {
            updateDetail();
        } else {
            int i = mRowKeys.indexOf(mListFocus);
            setFocus(i >= 0 ? i : mFocus);
        }
    }

    private void section(String title) {
        TextView v = text(title, 15, TEXT_MUTED, mMedium);
        v.setPadding(4, 16, 0, 8);
        mList.addView(v);
    }

    private void row(String key, String title, String subtitle, String right, int rightColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ROW_PAD, 0, ROW_PAD, 0);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 20, TEXT, mMedium);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView sub = text(subtitle, 15, TEXT_MUTED, mRegular);
        sub.setSingleLine();
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, 2, 0, 0);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = text(right, 15, rightColor, mMedium);
        state.setPadding(16, 0, 0, 0);
        row.addView(state);
        row.setOnClickListener(v -> {
            setFocus(mRows.indexOf(v));
            press(key);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ROW_H);
        lp.bottomMargin = ROW_GAP;
        mList.addView(row, lp);
        style(row, false, false, RADIUS);
        mRows.add(row);
        mRowKeys.add(key);
    }

    private void press(String key) {
        if (CLEANUP.equals(key)) {
            cleanup();
        } else {
            showDetail(key);
        }
    }

    /** Clears the cache of each package with a cache (also the packages the list does not show). */
    private void cleanup() {
        if (mSizes == null) {
            return;
        }
        List<String> pkgs = new ArrayList<>();
        long before = 0;
        for (Map.Entry<String, Apps.Sizes> e : mSizes.entrySet()) {
            if (e.getValue().cache > 0) {
                pkgs.add(e.getKey());
                before += e.getValue().cache;
            }
        }
        if (pkgs.isEmpty()) {
            mListNote = null;
            mCleanupText = getString(R.string.cleanup_nothing);
            rebuild();
            return;
        }
        long freed = before;
        boolean started = mJobs.cleanup(pkgs, (done, total) -> {
            mCleanupText = getString(R.string.cleanup_running, done + 1, total);
            mListNote = null;
            rebuild();
        }, (ok, error) -> {
            mCleanupText = ok ? getString(R.string.cleanup_done, size(freed))
                    : getString(R.string.failed, error);
            mSizes = null;
            reload();
        });
        if (!started) {
            mCleanupText = getString(R.string.busy);
            rebuild();
        }
    }

    // ------------------------------------------------------------------ the app screen

    private View buildDetail() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(DETAIL_MARGIN, 28, DETAIL_MARGIN, 24);
        mName = text("", 28, TEXT, mMedium);
        mName.setSingleLine();
        mName.setEllipsize(TextUtils.TruncateAt.END);
        screen.addView(mName);
        mInfo = text("", 15, TEXT_MUTED, mRegular);
        mInfo.setLineSpacing(4, 1);
        mInfo.setPadding(0, 12, 0, 0);
        screen.addView(mInfo);
        screen.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        mMessage = text("", 15, TEXT, mRegular);
        mMessage.setMaxLines(3);
        screen.addView(mMessage);
        mButtonScroll = new HorizontalScrollView(this);
        mButtonScroll.setFocusable(false);
        mButtonScroll.setHorizontalScrollBarEnabled(false);
        mButtons = new LinearLayout(this);
        mButtons.setOrientation(LinearLayout.HORIZONTAL);
        mButtonScroll.addView(mButtons);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, BUTTON_H);
        lp.topMargin = 16;
        screen.addView(mButtonScroll, lp);
        return screen;
    }

    private void showDetail(String pkg) {
        mListFocus = pkg;
        mDetail = pkg;
        mConfirm = 0;
        mNote = null;
        mListScreen.setVisibility(View.GONE);
        mDetailScreen.setVisibility(View.VISIBLE);
        mButtonViews.clear();
        mItems = mButtonViews;
        mFocus = 0;
        updateDetail();
    }

    private void showList() {
        mDetail = null;
        mConfirm = 0;
        mDetailScreen.setVisibility(View.GONE);
        mListScreen.setVisibility(View.VISIBLE);
        mItems = mRows;
        int i = mRowKeys.indexOf(mListFocus);
        setFocus(i >= 0 ? i : 0);
    }

    /** The texts and the buttons of the app screen, for the state of the app. */
    private void updateDetail() {
        Apps.App a = find(mDetail);
        if (a == null) {
            // Removed.
            showList();
            return;
        }
        mName.setText(a.label);
        StringBuilder info = new StringBuilder();
        info.append(getString(R.string.info_version, String.valueOf(a.versionName)));
        info.append('\n').append(a.system ? getString(a.updated ? R.string.info_image_updated
                : R.string.info_image) : a.installer != null
                ? getString(R.string.info_installer, a.installer) : a.pkg);
        info.append('\n').append(a.appBytes >= 0
                ? getString(R.string.info_sizes, size(a.appBytes), size(a.dataBytes - a.cacheBytes),
                        size(a.cacheBytes))
                : getString(R.string.info_sizes_unknown));
        info.append('\n').append(a.pssKb > 0
                ? getString(R.string.info_running, size(a.pssKb * 1024))
                : getString(R.string.info_not_running));
        info.append('\n').append(a.pkg);
        mInfo.setText(info);
        mMessage.setText(mNote != null ? mNote : "");
        mMessage.setTextColor(mNoteWarn ? WARN : ACCENT_LIGHT);

        List<Integer> labels = new ArrayList<>();
        mButtonActions.clear();
        if (mJobs.busy()) {
            mMessage.setText(R.string.working);
            mMessage.setTextColor(TEXT);
        } else {
            if (launchIntent(a.pkg) != null) {
                labels.add(R.string.btn_open);
                mButtonActions.add(() -> open(a.pkg));
            }
            if (a.pssKb > 0) {
                labels.add(R.string.btn_stop);
                mButtonActions.add(() -> confirm(R.string.btn_stop, R.string.confirm_stop,
                        () -> mJobs.stop(a.pkg, result(R.string.done_stop))));
            }
            if (a.cacheBytes > 0) {
                labels.add(R.string.btn_clear_cache);
                mButtonActions.add(() -> run(mJobs.clearCache(a.pkg, result(R.string.done_cache))));
            }
            if (a.dataBytes - a.cacheBytes > 0) {
                labels.add(R.string.btn_clear_data);
                mButtonActions.add(() -> confirm(R.string.btn_clear_data,
                        R.string.confirm_clear_data,
                        () -> mJobs.clearData(a.pkg, result(R.string.done_data))));
            }
            if (!a.system || a.updated) {
                int label = a.system ? R.string.btn_remove_update : R.string.btn_remove;
                labels.add(label);
                int warning = a.system ? R.string.confirm_remove_update : R.string.confirm_remove;
                mButtonActions.add(() -> confirm(label, warning,
                        () -> mJobs.remove(a.pkg, result(R.string.done_removed))));
            }
        }
        int keep = mFocus;
        mButtons.removeAllViews();
        mButtonViews.clear();
        for (int i = 0; i < labels.size(); i++) {
            int label = labels.get(i);
            TextView b = text(getString(mConfirm == label ? R.string.btn_confirm : label), 18,
                    TEXT, mMedium);
            b.setGravity(Gravity.CENTER);
            b.setPadding(BUTTON_PAD, 0, BUTTON_PAD, 0);
            b.setOnClickListener(v -> {
                int index = mButtonViews.indexOf(v);
                if (index != mFocus) {
                    setFocus(index);
                }
                if (index >= 0 && index < mButtonActions.size()) {
                    mButtonActions.get(index).run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, BUTTON_H);
            lp.rightMargin = BUTTON_GAP;
            mButtons.addView(b, lp);
            mButtonViews.add(b);
        }
        mFocus = Math.max(0, Math.min(keep, mButtonViews.size() - 1));
        for (int i = 0; i < mButtonViews.size(); i++) {
            style(mButtonViews.get(i), i == mFocus, false, 10);
        }
        if (!mButtonViews.isEmpty()) {
            revealButton(mButtonViews.get(mFocus));
        }
    }

    /** The first press shows the warning and "Press again"; the second press runs the action. */
    private void confirm(int label, int warning, Runnable action) {
        if (mConfirm != label) {
            mConfirm = label;
            mNote = getString(warning);
            mNoteWarn = true;
            updateDetail();
            return;
        }
        mConfirm = 0;
        mNote = null;
        action.run();
        updateDetail();
    }

    private void run(boolean started) {
        if (!started) {
            mNote = getString(R.string.busy);
            mNoteWarn = true;
        }
        updateDetail();
    }

    /** The result of a job: a note on the app screen, then the list again (sizes, memory). */
    private Jobs.Done result(int done) {
        return (ok, error) -> {
            mNote = ok ? getString(done) : getString(R.string.failed, String.valueOf(error));
            mNoteWarn = !ok;
            reload();
            if (mDetail != null) {
                updateDetail();
            }
        };
    }

    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLeanbackLaunchIntentForPackage(pkg);
    }

    private void open(String pkg) {
        Intent intent = launchIntent(pkg);
        try {
            if (intent != null) {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no activity for " + pkg, e);
        }
        mNote = getString(R.string.no_open);
        mNoteWarn = true;
        updateDetail();
    }

    // ------------------------------------------------------------------ focus and input

    private void setFocus(int index) {
        if (mItems.isEmpty()) {
            mFocus = 0;
            return;
        }
        int i = Math.max(0, Math.min(mItems.size() - 1, index));
        if (i != mFocus && mDetail != null && mConfirm != 0) {
            // Moving away from "Press again" cancels it.
            mConfirm = 0;
            mNote = null;
            mFocus = i;
            updateDetail();
            return;
        }
        mFocus = i;
        mEnterDown = false;
        float radius = mDetail != null ? 10 : RADIUS;
        for (int k = 0; k < mItems.size(); k++) {
            style(mItems.get(k), k == i, false, radius);
        }
        if (mDetail == null) {
            mListFocus = mRowKeys.get(i);
            reveal(mItems.get(i));
        } else {
            revealButton(mItems.get(i));
        }
    }

    /** Scrolls the list so that the focused row, and a section title above it, are visible. */
    private void reveal(View row) {
        mScroll.post(() -> {
            int index = mList.indexOfChild(row);
            if (index < 0) {
                return;
            }
            View above = index > 0 ? mList.getChildAt(index - 1) : null;
            int top = above != null && !(above instanceof LinearLayout) ? above.getTop()
                    : index == 0 ? 0 : row.getTop() - ROW_GAP;
            int bottom = row.getBottom() + ROW_GAP;
            int y = mScroll.getScrollY();
            int h = mScroll.getHeight();
            if (top < y) {
                mScroll.smoothScrollTo(0, top);
            } else if (bottom > y + h) {
                mScroll.smoothScrollTo(0, bottom - h);
            }
        });
    }

    /** Scrolls the buttons (portrait: they do not all fit) so that the focused one is visible. */
    private void revealButton(View b) {
        mButtonScroll.post(() -> {
            int x = mButtonScroll.getScrollX();
            int w = mButtonScroll.getWidth();
            if (b.getLeft() < x) {
                mButtonScroll.smoothScrollTo(b.getLeft(), 0);
            } else if (b.getRight() > x + w) {
                mButtonScroll.smoothScrollTo(b.getRight() - w, 0);
            }
        });
    }

    private void style(View v, boolean focus, boolean pressed, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(focus ? (pressed ? FILL_PRESSED : FILL_FOCUS) : FILL);
        d.setStroke(1, focus ? ACCENT : BORDER);
        v.setBackground(d);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    boolean next = code == KeyEvent.KEYCODE_DPAD_RIGHT
                            || code == KeyEvent.KEYCODE_DPAD_DOWN;
                    knob(next ? 1 : -1, event.getEventTime());
                }
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (mFocus >= mItems.size()) {
                    return true;
                }
                View v = mItems.get(mFocus);
                float radius = mDetail != null ? 10 : RADIUS;
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        mEnterDown = true;
                        style(v, true, true, radius);
                    }
                } else if (mEnterDown) {
                    mEnterDown = false;
                    style(v, true, false, radius);
                    v.performClick();
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

    /** Clockwise (+1) moves the focus down the list, or right on the app screen. No wrap. */
    private void knob(int step, long time) {
        if (time - mLastKnob < KNOB_GAP_MS) {
            return;
        }
        mLastKnob = time;
        setFocus(mFocus + step);
    }

    private TextView text(String s, int px, int color, Typeface face) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }
}
