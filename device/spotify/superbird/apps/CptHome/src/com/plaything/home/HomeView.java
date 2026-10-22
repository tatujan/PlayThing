/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The home screen in one view: four tiles, their labels, the focus dots, the Bluetooth status,
 * the rotate button, and the "Opening …" toast (docs/home-screen.md). Landscape: the tiles in
 * one row; portrait: in a 2 x 2 grid.
 *
 * All sizes are pixels, as in the design (800 x 480, 1 px = 1 design px). The display density
 * is 240, so dp units would make everything 1.5 times larger.
 */
final class HomeView extends View {
    interface Listener {
        void onLaunch(int tile);
    }

    static final int TILES = 4;
    // The focus index of the rotate button (after the last tile).
    static final int ROTATE = TILES;

    // Layout (design: "Layout: home screen"; four tiles of 160 px fit the 800 px width, the
    // design's 180 px tiles fit only three).
    private static final int TILE = 160;
    private static final int GAP = 24;
    private static final int ROW_GAP = 32;
    private static final float RADIUS = 14;
    private static final int ICON = 72;
    private static final int LABEL_GAP = 14;
    private static final int MARGIN_RIGHT = 24;
    private static final int MARGIN_TOP = 20;
    private static final int STATUS_ICON = 20;
    private static final int STATUS_GAP = 6;
    // Rotate button, left of the Bluetooth status: a 22 px icon in a 36 px square.
    private static final int ROTATE_SIZE = 36;
    private static final int ROTATE_ICON = 22;
    private static final int ROTATE_GAP = 14;
    private static final float ROTATE_RADIUS = 8;
    private static final int DOT_H = 6;
    private static final int DOT_W = 6;
    private static final int DOT_ACTIVE_W = 20;
    private static final int DOT_GAP = 8;
    private static final int DOT_BOTTOM = 24;
    private static final int TOAST_BOTTOM = 56;
    private static final int TOAST_PAD_X = 16;
    private static final int TOAST_PAD_Y = 8;
    private static final float TOAST_RADIUS = 8;
    private static final float FOCUS_SCALE = 1.06f;
    private static final long ANIM_MS = 180;
    private static final long TOAST_MS = 1400;
    // Glow: accent at 35 %, 32 px blur (CSS-like, sigma 16; BlurMaskFilter radius 27 gives
    // sigma 0.577 * 27 + 0.5 = 16). Drawn once into an 8-bit mask, outside the tile only.
    private static final int GLOW_PAD = 48;
    private static final float GLOW_RADIUS = 27;

    // Colors (design: "Color").
    private static final int TEXT = 0xFFE9E9ED;
    private static final int TEXT_MUTED = 0xFF9397AB;
    private static final int ICON_MUTED = 0xFFB2B6CA;
    private static final int ACCENT = 0xFF34C759;
    private static final int ACCENT_LIGHT = 0xFFB4F0C3;
    private static final int NEUTRAL_700 = 0xFF595D6C;
    private static final int NEUTRAL_600 = 0xFF75798C;
    private static final int SURFACE = 0xFF232532;
    private static final int TILE_FILL = 0x8C232532;
    private static final int TILE_FILL_FOCUS = 0x1F34C759;
    private static final int TILE_FILL_PRESSED = 0x3334C759;
    private static final int TILE_BORDER = 0x1AE9E9ED;
    private static final int GLOW = 0x5934C759;

    private final Listener mListener;
    private final String[] mLabels = new String[TILES];
    private final Drawable[] mIcons = new Drawable[TILES];
    private final Drawable mBtIcon;
    private final Drawable mBtOffIcon;
    private final Drawable mRotateIcon;
    private final RectF mRotateRect = new RectF();
    private final RectF[] mTiles = new RectF[TILES];
    private final float[] mScale = {1, 1, 1, 1};
    private final float[] mDotW = {DOT_W, DOT_W, DOT_W, DOT_W};
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF mRect = new RectF();
    private final ValueAnimator mAnim = ValueAnimator.ofFloat(0, 1);
    private final float[] mFromScale = new float[TILES];
    private final float[] mFromDotW = new float[TILES];
    private final Runnable mHideToast = this::hideToast;
    private Bitmap mGlow;

    private int mFocus;
    private boolean mPressed;
    private int mTouchTile = -1;
    private String mStatus = "";
    private boolean mBtConnected;
    private String mToast;

    HomeView(Context context, Listener listener) {
        super(context);
        mListener = listener;
        mLabels[0] = context.getString(R.string.carplay);
        mLabels[1] = context.getString(R.string.bluetooth);
        mLabels[2] = context.getString(R.string.settings);
        mLabels[3] = context.getString(R.string.apps);
        mIcons[0] = context.getDrawable(R.drawable.ic_car_profile).mutate();
        mIcons[1] = context.getDrawable(R.drawable.ic_bluetooth).mutate();
        mIcons[2] = context.getDrawable(R.drawable.ic_gear_six).mutate();
        mIcons[3] = context.getDrawable(R.drawable.ic_squares_four).mutate();
        mBtIcon = context.getDrawable(R.drawable.ic_bluetooth).mutate();
        mBtOffIcon = context.getDrawable(R.drawable.ic_bluetooth_slash).mutate();
        mRotateIcon = context.getDrawable(R.drawable.ic_device_rotate).mutate();
        for (int i = 0; i < TILES; i++) {
            mTiles[i] = new RectF();
        }

        Typeface medium = Typeface.create(Typeface.SANS_SERIF, 500, false);
        mLabel.setTypeface(medium);
        mLabel.setTextSize(20);
        mLabel.setTextAlign(Paint.Align.CENTER);
        mSmall.setTypeface(Typeface.create(Typeface.SANS_SERIF, 400, false));
        mSmall.setTextSize(15);
        mBorder.setStyle(Paint.Style.STROKE);
        mBorder.setStrokeWidth(1);

        mAnim.setDuration(ANIM_MS);
        mAnim.setInterpolator(new DecelerateInterpolator());
        mAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            for (int i = 0; i < TILES; i++) {
                float s = i == mFocus ? FOCUS_SCALE : 1;
                float w = i == mFocus ? DOT_ACTIVE_W : DOT_W;
                mScale[i] = mFromScale[i] + (s - mFromScale[i]) * f;
                mDotW[i] = mFromDotW[i] + (w - mFromDotW[i]) * f;
            }
            invalidate();
        });
        mScale[0] = FOCUS_SCALE;
        mDotW[0] = DOT_ACTIVE_W;
        applyColors();
        setDefaultFocusHighlightEnabled(false);
    }

    int getFocusedTile() {
        return mFocus;
    }

    /**
     * Moves the focus to a tile, or to the rotate button (ROTATE). Colors change at once; scale
     * and dot width in 180 ms.
     */
    void setFocusedTile(int tile) {
        if (tile < 0 || tile > ROTATE || tile == mFocus) {
            return;
        }
        mFocus = tile;
        mPressed = false;
        System.arraycopy(mScale, 0, mFromScale, 0, TILES);
        System.arraycopy(mDotW, 0, mFromDotW, 0, TILES);
        applyColors();
        mAnim.cancel();
        mAnim.start();
    }

    void setTilePressed(int tile, boolean pressed) {
        boolean p = pressed && tile == mFocus;
        if (p != mPressed) {
            mPressed = p;
            invalidate();
        }
    }

    void setStatus(String text, boolean connected) {
        if (!text.equals(mStatus) || connected != mBtConnected) {
            mStatus = text;
            mBtConnected = connected;
            invalidate();
        }
    }

    void showToast(String text) {
        mToast = text;
        removeCallbacks(mHideToast);
        postDelayed(mHideToast, TOAST_MS);
        invalidate();
    }

    void hideToast() {
        removeCallbacks(mHideToast);
        if (mToast != null) {
            mToast = null;
            invalidate();
        }
    }

    private void applyColors() {
        for (int i = 0; i < TILES; i++) {
            mIcons[i].setTint(i == mFocus ? ACCENT_LIGHT : ICON_MUTED);
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // The tiles and their labels are one group, centered on the screen: one row in
        // landscape, two columns in portrait (four tiles of 160 px do not fit 480 px).
        Paint.FontMetrics fm = mLabel.getFontMetrics();
        int cols = w >= h ? TILES : 2;
        int rows = (TILES + cols - 1) / cols;
        float cell = TILE + LABEL_GAP + (fm.descent - fm.ascent);
        float top = Math.round((h - (rows * cell + (rows - 1) * ROW_GAP)) / 2);
        float left = (w - (cols * TILE + (cols - 1) * GAP)) / 2;
        for (int i = 0; i < TILES; i++) {
            float l = left + (i % cols) * (TILE + GAP);
            float t = top + (i / cols) * (cell + ROW_GAP);
            mTiles[i].set(l, t, l + TILE, t + TILE);
        }
        if (mGlow == null) {
            mGlow = makeGlow();
        }
    }

    private static Bitmap makeGlow() {
        int size = TILE + 2 * GLOW_PAD;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(GLOW_RADIUS, BlurMaskFilter.Blur.NORMAL));
        RectF r = new RectF(GLOW_PAD, GLOW_PAD, GLOW_PAD + TILE, GLOW_PAD + TILE);
        c.drawRoundRect(r, RADIUS, RADIUS, p);
        // Only outside the tile, as a CSS box shadow: the tile fill is translucent.
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawRoundRect(r, RADIUS, RADIUS, clear);
        return b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();

        for (int i = 0; i < TILES; i++) {
            boolean focus = i == mFocus;
            RectF t = mTiles[i];
            canvas.save();
            canvas.scale(mScale[i], mScale[i], t.centerX(), t.centerY());
            if (focus && mGlow != null) {
                mGlowPaint.setColor(GLOW);
                canvas.drawBitmap(mGlow, t.left - GLOW_PAD, t.top - GLOW_PAD, mGlowPaint);
            }
            mFill.setColor(focus ? (mPressed ? TILE_FILL_PRESSED : TILE_FILL_FOCUS) : TILE_FILL);
            canvas.drawRoundRect(t, RADIUS, RADIUS, mFill);
            mBorder.setColor(focus ? ACCENT : TILE_BORDER);
            mRect.set(t.left + 0.5f, t.top + 0.5f, t.right - 0.5f, t.bottom - 0.5f);
            canvas.drawRoundRect(mRect, RADIUS, RADIUS, mBorder);
            int cx = (int) t.centerX();
            int cy = (int) t.centerY();
            mIcons[i].setBounds(cx - ICON / 2, cy - ICON / 2, cx + ICON / 2, cy + ICON / 2);
            mIcons[i].draw(canvas);
            canvas.restore();

            mLabel.setColor(focus ? TEXT : TEXT_MUTED);
            canvas.drawText(mLabels[i], t.centerX(), t.bottom + LABEL_GAP - mLabel.ascent(), mLabel);
        }

        // Bluetooth status, top right: 20 px icon, 6 px, 15 px text.
        int color = mBtConnected ? ACCENT_LIGHT : NEUTRAL_600;
        float textW = mSmall.measureText(mStatus);
        float textX = w - MARGIN_RIGHT - textW;
        float mid = MARGIN_TOP + STATUS_ICON / 2f;
        Drawable bt = mBtConnected ? mBtIcon : mBtOffIcon;
        int iconL = (int) (textX - STATUS_GAP - STATUS_ICON);
        bt.setBounds(iconL, MARGIN_TOP, iconL + STATUS_ICON, MARGIN_TOP + STATUS_ICON);
        bt.setTint(color);
        bt.draw(canvas);
        mSmall.setColor(color);
        canvas.drawText(mStatus, textX, mid - (mSmall.ascent() + mSmall.descent()) / 2, mSmall);

        // Rotate button, left of the status. Focused: the tile focus colors.
        float right = iconL - ROTATE_GAP;
        mRotateRect.set(right - ROTATE_SIZE, mid - ROTATE_SIZE / 2f, right, mid + ROTATE_SIZE / 2f);
        boolean rotateFocus = mFocus == ROTATE;
        if (rotateFocus) {
            mFill.setColor(mPressed ? TILE_FILL_PRESSED : TILE_FILL_FOCUS);
            canvas.drawRoundRect(mRotateRect, ROTATE_RADIUS, ROTATE_RADIUS, mFill);
            mBorder.setColor(ACCENT);
            mRect.set(mRotateRect);
            mRect.inset(0.5f, 0.5f);
            canvas.drawRoundRect(mRect, ROTATE_RADIUS, ROTATE_RADIUS, mBorder);
        }
        int rx = (int) mRotateRect.centerX();
        int ry = (int) mRotateRect.centerY();
        mRotateIcon.setBounds(rx - ROTATE_ICON / 2, ry - ROTATE_ICON / 2, rx + ROTATE_ICON / 2,
                ry + ROTATE_ICON / 2);
        mRotateIcon.setTint(rotateFocus ? ACCENT_LIGHT : ICON_MUTED);
        mRotateIcon.draw(canvas);

        // Focus dots: one for each tile; the active one is wider.
        float total = (TILES - 1) * DOT_GAP;
        for (int i = 0; i < TILES; i++) {
            total += mDotW[i];
        }
        float x = (w - total) / 2;
        float dotTop = h - DOT_BOTTOM - DOT_H;
        for (int i = 0; i < TILES; i++) {
            mFill.setColor(i == mFocus ? ACCENT : NEUTRAL_700);
            mRect.set(x, dotTop, x + mDotW[i], dotTop + DOT_H);
            canvas.drawRoundRect(mRect, DOT_H / 2f, DOT_H / 2f, mFill);
            x += mDotW[i] + DOT_GAP;
        }

        // Toast: 56 px above the bottom, surface fill, 1 px neutral-700 border, radius 8.
        if (mToast != null) {
            Paint.FontMetrics fm = mSmall.getFontMetrics();
            float tw = mSmall.measureText(mToast);
            float th = fm.descent - fm.ascent;
            float bottom = h - TOAST_BOTTOM;
            float top = bottom - th - 2 * TOAST_PAD_Y;
            float left = (w - tw) / 2 - TOAST_PAD_X;
            mRect.set(left, top, w - left, bottom);
            mFill.setColor(SURFACE);
            canvas.drawRoundRect(mRect, TOAST_RADIUS, TOAST_RADIUS, mFill);
            mBorder.setColor(NEUTRAL_700);
            mRect.inset(0.5f, 0.5f);
            canvas.drawRoundRect(mRect, TOAST_RADIUS, TOAST_RADIUS, mBorder);
            mSmall.setColor(TEXT);
            canvas.drawText(mToast, left + TOAST_PAD_X, top + TOAST_PAD_Y - fm.ascent, mSmall);
        }
    }

    /** The tile and its label: the touch area of a tile. ROTATE: the rotate button, 8 px more. */
    private int tileAt(float x, float y) {
        if (x >= mRotateRect.left - 8 && x <= mRotateRect.right + 8
                && y >= mRotateRect.top - 8 && y <= mRotateRect.bottom + 8) {
            return ROTATE;
        }
        for (int i = 0; i < TILES; i++) {
            RectF t = mTiles[i];
            if (x >= t.left && x <= t.right && y >= t.top
                    && y <= t.bottom + LABEL_GAP + mLabel.getTextSize() + 8) {
                return i;
            }
        }
        return -1;
    }

    // Touch: a tap on another tile moves the focus to it; a tap on the focused tile opens it.
    // A tap on the rotate button rotates at once.
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTouchTile = tileAt(e.getX(), e.getY());
                setTilePressed(mTouchTile, true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mTouchTile >= 0 && tileAt(e.getX(), e.getY()) != mTouchTile) {
                    setTilePressed(mTouchTile, false);
                    mTouchTile = -1;
                }
                return true;
            case MotionEvent.ACTION_UP:
                int tile = mTouchTile;
                mTouchTile = -1;
                setTilePressed(tile, false);
                if (tile == mFocus || tile == ROTATE) {
                    mListener.onLaunch(tile);
                } else if (tile >= 0) {
                    setFocusedTile(tile);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                setTilePressed(mTouchTile, false);
                mTouchTile = -1;
                return true;
        }
        return super.onTouchEvent(e);
    }
}
