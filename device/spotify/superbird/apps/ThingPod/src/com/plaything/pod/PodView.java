/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.pod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The ThingPod screen in one view, in the style of the iPod classic (docs/thingpod.md): a title
 * bar, a menu list with a blue selection bar (in the main menus, the cover art in the right
 * half), and the Now Playing screen with the cover, the song, and a progress bar (a volume bar
 * while the knob turns). There is no click wheel on the screen: the Car Thing's knob is the wheel.
 * The screen sits in a window of brushed aluminium, behind dark glass, as on the iPod: the body
 * texture and the beveled window are drawn once into a bitmap; an inner shadow and a glass gloss
 * are drawn over the screen.
 *
 * All sizes are pixels (density 240), as in CptHome.
 */
final class PodView extends View {
    interface Listener {
        /** A tap on a row: select it and press it. */
        void onRowTapped(int index);
    }

    static final class Item {
        final String label;
        final boolean more;
        final String right;
        final boolean enabled;

        Item(String label, boolean more, String right, boolean enabled) {
            this.label = label;
            this.more = more;
            this.right = right;
            this.enabled = enabled;
        }
    }

    static final class Menu {
        final String title;
        final List<Item> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        // The cover art in the right half (landscape only).
        final boolean split;
        int selected;
        int top;

        Menu(String title, boolean split) {
            this.title = title;
            this.split = split;
        }

        Menu add(String label, boolean more, String right, Runnable action) {
            items.add(new Item(label, more, right, action != null));
            actions.add(action);
            return this;
        }
    }

    // Layout: METAL px of aluminium, then GLASS px of dark glass, then the screen (BEZEL px from
    // each edge of the display).
    private static final int METAL = 26;
    private static final int GLASS = 9;
    private static final int BEZEL = METAL + GLASS;
    private static final float WINDOW_RADIUS = 20;
    private static final float SCREEN_RADIUS = 10;
    private static final float CUT_W = 5;
    private static final int TITLE_H = 48;
    private static final int ROW_H = 56;
    private static final int ROW_PAD = 16;
    private static final int SCROLL_W = 12;
    private static final int BAR_H = 18;
    private static final long VOLUME_MS = 1500;

    // Colors of the iPod classic.
    private static final int TITLE_TOP = 0xFFFDFDFD;
    private static final int TITLE_BOTTOM = 0xFFCFD1D4;
    private static final int TITLE_LINE = 0xFF8E9196;
    private static final int TEXT = 0xFF000000;
    private static final int TEXT_GRAY = 0xFF6D7076;
    private static final int LIST_BG = 0xFFFFFFFF;
    private static final int SELECT_TOP = 0xFF5AA9F3;
    private static final int SELECT_BOTTOM = 0xFF1B66D2;
    private static final int SELECT_TEXT = 0xFFFFFFFF;
    private static final int TRACK = 0xFFE3E5E8;
    private static final int TRACK_LINE = 0xFF9A9EA5;
    private static final int THUMB = 0xFF9DA4AF;
    private static final int ART_BG_TOP = 0xFFE8EAED;
    private static final int ART_BG_BOTTOM = 0xFFB9BDC3;
    private static final int NOTE = 0xFF8E939B;
    // Glass, the beveled cut of the window (dark at the top edge, light at the bottom edge: light
    // from above), the inner shadow of the screen, and the gloss of the glass.
    private static final int GLASS_TOP = 0xFF1E2024;
    private static final int GLASS_BOTTOM = 0xFF09090B;
    private static final int CUT_TOP = 0xB3000000;
    private static final int CUT_BOTTOM = 0xFFFFFFFF;
    private static final int SHADOW = 0x73000000;
    private static final int SHADOW_SIDE = 0x40000000;
    private static final int GLOSS_TOP = 0x33FFFFFF;
    private static final int GLOSS_BOTTOM = 0x06FFFFFF;
    private static final int SHADOW_TOP_H = 16;
    private static final int SHADOW_SIDE_W = 7;

    private final Listener mListener;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBig = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArtPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF mRect = new RectF();
    private final Rect mSrc = new Rect();
    private final Path mPath = new Path();
    private final Path mScreen = new Path();
    private final RectF mScreenRect = new RectF();
    private final Path mGloss = new Path();
    private final Paint mOverlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap mBody;
    private Shader mShadowTop;
    private Shader mShadowLeft;
    private Shader mShadowRight;
    private Shader mGlossShader;
    // The screen inside the body: its size (the drawing below uses screen coordinates).
    private int mSw;
    private int mSh;
    private final Runnable mHideVolume = () -> {
        mVolume = -1;
        invalidate();
    };

    private Menu mMenu;
    // The skip buttons of Now Playing (knob press and hold): the selected one, or -1 when hidden.
    static final int SKIP_PREVIOUS = 0;
    static final int SKIP_NEXT = 1;
    private static final int SKIP_W = 120;
    private static final int SKIP_H = 54;
    private static final int SKIP_GAP = 32;
    private int mSkip = -1;
    private boolean mNowPlaying;
    private Bitmap mArt;
    private boolean mPlaying;
    private String mSongTitle = "";
    private String mSongArtist = "";
    private String mSongAlbum = "";
    private String mSongIndex = "";
    private int mPosition;
    private int mDuration;
    private float mVolume = -1;

    PodView(Context context, Listener listener) {
        super(context);
        mListener = listener;
        Typeface bold = Typeface.create(Typeface.SANS_SERIF, 700, false);
        Typeface medium = Typeface.create(Typeface.SANS_SERIF, 500, false);
        mTitle.setTypeface(bold);
        mTitle.setTextSize(26);
        mTitle.setTextAlign(Paint.Align.CENTER);
        mTitle.setColor(TEXT);
        mText.setTypeface(medium);
        mText.setTextSize(28);
        mBig.setTypeface(bold);
        mBig.setTextSize(34);
        mSmall.setTypeface(medium);
        mSmall.setTextSize(22);
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(1);
        setDefaultFocusHighlightEnabled(false);
    }

    void showMenu(Menu menu) {
        mMenu = menu;
        mNowPlaying = false;
        keepVisible();
        invalidate();
    }

    void showNowPlaying() {
        mNowPlaying = true;
        invalidate();
    }

    boolean nowPlaying() {
        return mNowPlaying;
    }

    /** Shows the skip buttons in place of the progress bar, with one of them selected. */
    void showSkip(int selected) {
        mSkip = selected;
        invalidate();
    }

    void hideSkip() {
        if (mSkip >= 0) {
            mSkip = -1;
            invalidate();
        }
    }

    /** The selected skip button, or -1 when they are hidden. */
    int skip() {
        return mSkip;
    }

    void moveSkip(int step) {
        if (mSkip >= 0) {
            int i = Math.max(SKIP_PREVIOUS, Math.min(SKIP_NEXT, mSkip + step));
            if (i != mSkip) {
                mSkip = i;
                invalidate();
            }
        }
    }

    Menu menu() {
        return mMenu;
    }

    void setArt(Bitmap art) {
        if (art != mArt) {
            mArt = art;
            invalidate();
        }
    }

    void setSong(String title, String artist, String album, String index) {
        mSongTitle = title;
        mSongArtist = artist;
        mSongAlbum = album;
        mSongIndex = index;
        invalidate();
    }

    /** Redraws only for a change that shows: the title bar sign, or the progress bar. */
    void setPlayback(boolean playing, int position, int duration) {
        boolean sign = playing != mPlaying || (duration > 0) != (mDuration > 0);
        boolean progress = position / 1000 != mPosition / 1000 || duration != mDuration;
        mPlaying = playing;
        mPosition = position;
        mDuration = duration;
        if (sign || (progress && mNowPlaying)) {
            invalidate();
        }
    }

    /** Shows the volume bar (0 to 1) instead of the progress bar for 1.5 s. */
    void showVolume(float level) {
        mVolume = level;
        removeCallbacks(mHideVolume);
        postDelayed(mHideVolume, VOLUME_MS);
        invalidate();
    }

    /** Moves the selection; no wrap at the ends. */
    void move(int step) {
        if (mMenu == null || mMenu.items.isEmpty()) {
            return;
        }
        int i = Math.max(0, Math.min(mMenu.items.size() - 1, mMenu.selected + step));
        if (i != mMenu.selected) {
            mMenu.selected = i;
            keepVisible();
            invalidate();
        }
    }

    /** Portrait main menus: the cover is a square at the bottom, below the list. */
    private boolean artBelow() {
        return mMenu != null && mMenu.split && !landscape()
                && mSh - mSw >= TITLE_H + 3 * ROW_H;
    }

    private int listBottom() {
        return artBelow() ? mSh - mSw : mSh;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - TITLE_H) / ROW_H);
    }

    private void keepVisible() {
        if (mMenu == null || mSh <= 0) {
            return;
        }
        int rows = visibleRows();
        if (mMenu.selected < mMenu.top) {
            mMenu.top = mMenu.selected;
        } else if (mMenu.selected >= mMenu.top + rows) {
            mMenu.top = mMenu.selected - rows + 1;
        }
        mMenu.top = Math.max(0, Math.min(mMenu.top, Math.max(0, mMenu.items.size() - rows)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mSw = w - 2 * BEZEL;
        mSh = h - 2 * BEZEL;
        mScreenRect.set(BEZEL, BEZEL, w - BEZEL, h - BEZEL);
        mScreen.reset();
        mScreen.addRoundRect(mScreenRect, SCREEN_RADIUS, SCREEN_RADIUS, Path.Direction.CW);
        RectF s = mScreenRect;
        mShadowTop = new LinearGradient(0, s.top, 0, s.top + SHADOW_TOP_H, SHADOW, 0,
                Shader.TileMode.CLAMP);
        mShadowLeft = new LinearGradient(s.left, 0, s.left + SHADOW_SIDE_W, 0, SHADOW_SIDE, 0,
                Shader.TileMode.CLAMP);
        mShadowRight = new LinearGradient(s.right, 0, s.right - SHADOW_SIDE_W, 0, SHADOW_SIDE, 0,
                Shader.TileMode.CLAMP);
        // The gloss: the upper left part of the glass, with a curved lower edge.
        float glossBottom = s.top + s.height() * 0.46f;
        mGloss.reset();
        mGloss.moveTo(s.left, s.top);
        mGloss.lineTo(s.right, s.top);
        mGloss.lineTo(s.right, s.top + s.height() * 0.12f);
        mGloss.quadTo(s.centerX(), s.top + s.height() * 0.30f, s.left, glossBottom);
        mGloss.close();
        mGlossShader = new LinearGradient(0, s.top, 0, glossBottom, GLOSS_TOP, GLOSS_BOTTOM,
                Shader.TileMode.CLAMP);
        if (mBody != null) {
            mBody.recycle();
        }
        mBody = w > 0 && h > 0 ? makeBody(w, h) : null;
        keepVisible();
    }

    /**
     * The body: brushed aluminium (fine horizontal lines, a broad sheen, lighter at the top) with
     * the beveled window and its dark glass. Made once for each size; RGB_565 (the noise of the
     * brush lines hides the steps of 16-bit color).
     */
    private static Bitmap makeBody(int w, int h) {
        int[] px = new int[w * h];
        java.util.Random r = new java.util.Random(7);
        for (int y = 0; y < h; y++) {
            float base = 228 - 34 * y / (float) h;
            float row = (float) r.nextGaussian() * 3.2f;
            float walk = 0;
            for (int x = 0; x < w; x++) {
                if ((x & 3) == 0) {
                    walk = Math.max(-5, Math.min(5, walk + (float) r.nextGaussian() * 1.1f));
                }
                float sheen = 9 * (float) Math.cos((x / (float) w - 0.32f) * Math.PI * 1.3f);
                int v = Math.max(3, Math.min(250, Math.round(base + row + walk + sheen)));
                px[y * w + x] = 0xFF000000 | (v - 3) << 16 | (v - 1) << 8 | (v + 3);
            }
        }
        Bitmap argb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        argb.setPixels(px, 0, w, 0, 0, w, h);
        Canvas c = new Canvas(argb);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF win = new RectF(METAL, METAL, w - METAL, h - METAL);
        // The cut edge around the window: a shadow at the top, a light line at the bottom.
        RectF cut = new RectF(win);
        cut.inset(-CUT_W / 2, -CUT_W / 2);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(CUT_W);
        p.setShader(new LinearGradient(0, cut.top, 0, cut.bottom, CUT_TOP, CUT_BOTTOM,
                Shader.TileMode.CLAMP));
        c.drawRoundRect(cut, WINDOW_RADIUS + CUT_W / 2, WINDOW_RADIUS + CUT_W / 2, p);
        // The dark glass.
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, win.top, 0, win.bottom, GLASS_TOP, GLASS_BOTTOM,
                Shader.TileMode.CLAMP));
        c.drawRoundRect(win, WINDOW_RADIUS, WINDOW_RADIUS, p);
        Bitmap out = argb.copy(Bitmap.Config.RGB_565, false);
        argb.recycle();
        return out;
    }

    private boolean landscape() {
        return mSw > mSh;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // The body with its window, then the screen (clipped to its rounded corners), then the
        // inner shadow and the gloss of the glass over it.
        if (mBody != null) {
            canvas.drawBitmap(mBody, 0, 0, null);
        }
        canvas.save();
        canvas.clipPath(mScreen);
        canvas.save();
        canvas.translate(BEZEL, BEZEL);
        drawScreen(canvas, mSw, mSh);
        canvas.restore();
        RectF s = mScreenRect;
        mOverlay.setShader(mShadowTop);
        canvas.drawRect(s.left, s.top, s.right, s.top + SHADOW_TOP_H, mOverlay);
        mOverlay.setShader(mShadowLeft);
        canvas.drawRect(s.left, s.top, s.left + SHADOW_SIDE_W, s.bottom, mOverlay);
        mOverlay.setShader(mShadowRight);
        canvas.drawRect(s.right - SHADOW_SIDE_W, s.top, s.right, s.bottom, mOverlay);
        mOverlay.setShader(mGlossShader);
        canvas.drawPath(mGloss, mOverlay);
        canvas.restore();
    }

    private void drawScreen(Canvas canvas, int w, int h) {
        canvas.drawColor(LIST_BG);
        if (mNowPlaying) {
            drawNowPlaying(canvas, w, h);
            drawTitleBar(canvas, w, getContext().getString(R.string.now_playing));
        } else if (mMenu != null) {
            boolean split = mMenu.split && landscape();
            int listW = split ? w / 2 : w;
            drawList(canvas, listW, h);
            if (split) {
                drawArtPane(canvas, listW, TITLE_H, w, h);
            } else if (artBelow()) {
                drawArtPane(canvas, 0, listBottom(), w, h);
            }
            drawTitleBar(canvas, w, mMenu.title);
        }
    }

    private void drawTitleBar(Canvas canvas, int w, String title) {
        mFill.setShader(new LinearGradient(0, 0, 0, TITLE_H, TITLE_TOP, TITLE_BOTTOM,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, TITLE_H, mFill);
        mFill.setShader(null);
        mFill.setColor(TITLE_LINE);
        canvas.drawRect(0, TITLE_H - 1, w, TITLE_H, mFill);
        float base = TITLE_H / 2f - (mTitle.ascent() + mTitle.descent()) / 2;
        canvas.drawText(ellipsize(title, mTitle, w - 120), w / 2f, base, mTitle);
        // Play or pause sign at the left, as on the iPod.
        if (mPlaying || mDuration > 0) {
            float x = 18;
            float cy = TITLE_H / 2f;
            mFill.setColor(0xFF2B2E33);
            if (mPlaying) {
                mPath.reset();
                mPath.moveTo(x, cy - 10);
                mPath.lineTo(x + 16, cy);
                mPath.lineTo(x, cy + 10);
                mPath.close();
                canvas.drawPath(mPath, mFill);
            } else {
                canvas.drawRect(x, cy - 10, x + 5, cy + 10, mFill);
                canvas.drawRect(x + 10, cy - 10, x + 15, cy + 10, mFill);
            }
        }
    }

    private void drawList(Canvas canvas, int listW, int h) {
        int rows = visibleRows();
        int count = mMenu.items.size();
        boolean scroll = count > rows;
        int textRight = listW - ROW_PAD - (scroll ? SCROLL_W + 4 : 0);
        for (int r = 0; r < rows && mMenu.top + r < count; r++) {
            int i = mMenu.top + r;
            Item item = mMenu.items.get(i);
            int top = TITLE_H + r * ROW_H;
            boolean sel = i == mMenu.selected && item.enabled;
            if (sel) {
                mFill.setShader(new LinearGradient(0, top, 0, top + ROW_H, SELECT_TOP,
                        SELECT_BOTTOM, Shader.TileMode.CLAMP));
                canvas.drawRect(0, top, scroll ? listW - SCROLL_W - 2 : listW, top + ROW_H, mFill);
                mFill.setShader(null);
            }
            float base = top + ROW_H / 2f - (mText.ascent() + mText.descent()) / 2;
            int right = textRight;
            if (item.more) {
                drawChevron(canvas, right - 6, top + ROW_H / 2f, sel ? SELECT_TEXT : TEXT_GRAY);
                right -= 30;
            }
            if (item.right != null) {
                mSmall.setColor(sel ? SELECT_TEXT : TEXT_GRAY);
                float rw = mSmall.measureText(item.right);
                float rb = top + ROW_H / 2f - (mSmall.ascent() + mSmall.descent()) / 2;
                canvas.drawText(item.right, right - rw, rb, mSmall);
                right -= (int) rw + 16;
            }
            mText.setColor(sel ? SELECT_TEXT : item.enabled ? TEXT : TEXT_GRAY);
            canvas.drawText(ellipsize(item.label, mText, right - ROW_PAD), ROW_PAD, base, mText);
        }
        if (scroll) {
            int trackTop = TITLE_H + 2;
            int trackH = rows * ROW_H - 4;
            mFill.setColor(TRACK);
            mRect.set(listW - SCROLL_W - 1, trackTop, listW - 1, trackTop + trackH);
            canvas.drawRect(mRect, mFill);
            mLine.setColor(TRACK_LINE);
            canvas.drawRect(mRect, mLine);
            float thumbH = Math.max(24, trackH * (float) rows / count);
            float thumbTop = trackTop + (trackH - thumbH) * mMenu.top / (float) (count - rows);
            mFill.setColor(THUMB);
            mRect.set(listW - SCROLL_W, thumbTop, listW - 2, thumbTop + thumbH);
            canvas.drawRoundRect(mRect, 3, 3, mFill);
        }
    }

    private void drawChevron(Canvas canvas, float x, float cy, int color) {
        mLine.setColor(color);
        mLine.setStrokeWidth(4);
        mPath.reset();
        mPath.moveTo(x - 8, cy - 11);
        mPath.lineTo(x + 2, cy);
        mPath.lineTo(x - 8, cy + 11);
        canvas.drawPath(mPath, mLine);
        mLine.setStrokeWidth(1);
    }

    /** The right half of the main menus: the cover, cropped to fill, or a music note. */
    private void drawArtPane(Canvas canvas, int left, int top, int right, int bottom) {
        mFill.setShader(new LinearGradient(0, top, 0, bottom, ART_BG_TOP, ART_BG_BOTTOM,
                Shader.TileMode.CLAMP));
        canvas.drawRect(left, top, right, bottom, mFill);
        mFill.setShader(null);
        mFill.setColor(TITLE_LINE);
        if (left > 0) {
            canvas.drawRect(left, top, left + 1, bottom, mFill);
        } else {
            canvas.drawRect(left, top, right, top + 1, mFill);
        }
        if (mArt != null) {
            mRect.set(left > 0 ? left + 1 : left, left > 0 ? top : top + 1, right, bottom);
            drawCropped(canvas, mArt, mRect);
        } else {
            drawNote(canvas, (left + right) / 2f, (top + bottom) / 2f, 70);
        }
    }

    private void drawNowPlaying(Canvas canvas, int w, int h) {
        boolean land = landscape();
        // The cover: as large as the space gives (the bar and the times need about 110 px).
        int art = land ? Math.min(280, h - TITLE_H - 110) : Math.min(300, w - 80);
        float artLeft = land ? 32 : (w - art) / 2f;
        float artTop = TITLE_H + 20;
        mRect.set(artLeft, artTop, artLeft + art, artTop + art);
        if (mArt != null) {
            drawCropped(canvas, mArt, mRect);
        } else {
            mFill.setShader(new LinearGradient(0, mRect.top, 0, mRect.bottom, ART_BG_TOP,
                    ART_BG_BOTTOM, Shader.TileMode.CLAMP));
            canvas.drawRect(mRect, mFill);
            mFill.setShader(null);
            drawNote(canvas, mRect.centerX(), mRect.centerY(), art / 3f);
        }
        mLine.setColor(TRACK_LINE);
        canvas.drawRect(mRect, mLine);

        // The song: index, title, artist, album.
        float textLeft = land ? artLeft + art + 32 : 24;
        float textW = (land ? w - 28 : w - 24) - textLeft;
        float y = land ? artTop + 30 : artTop + art + 44;
        Paint.Align align = land ? Paint.Align.LEFT : Paint.Align.CENTER;
        float x = land ? textLeft : w / 2f;
        mSmall.setTextAlign(align);
        mBig.setTextAlign(align);
        mText.setTextAlign(align);
        mSmall.setColor(TEXT_GRAY);
        canvas.drawText(mSongIndex, x, y, mSmall);
        y += 52;
        mBig.setColor(TEXT);
        canvas.drawText(ellipsize(mSongTitle, mBig, textW), x, y, mBig);
        y += 44;
        mText.setColor(TEXT);
        canvas.drawText(ellipsize(mSongArtist, mText, textW), x, y, mText);
        y += 40;
        mText.setColor(TEXT_GRAY);
        canvas.drawText(ellipsize(mSongAlbum, mText, textW), x, y, mText);
        mSmall.setTextAlign(Paint.Align.LEFT);
        mBig.setTextAlign(Paint.Align.LEFT);
        mText.setTextAlign(Paint.Align.LEFT);

        // Progress bar; the volume bar while the knob turns; the skip buttons after a knob hold.
        float barLeft = 32;
        float barRight = w - 32;
        float barTop = h - (land ? 66 : 96);
        if (mSkip >= 0) {
            float top = barTop + BAR_H / 2f - SKIP_H / 2f + 8;
            float left = w / 2f - SKIP_W - SKIP_GAP / 2f;
            drawSkipButton(canvas, left, top, false, mSkip == SKIP_PREVIOUS);
            drawSkipButton(canvas, w / 2f + SKIP_GAP / 2f, top, true, mSkip == SKIP_NEXT);
        } else if (mVolume >= 0) {
            drawSpeaker(canvas, barLeft, barTop + BAR_H / 2f, false);
            drawSpeaker(canvas, barRight - 24, barTop + BAR_H / 2f, true);
            drawBar(canvas, barLeft + 36, barTop, barRight - 36, mVolume);
        } else {
            float f = mDuration > 0 ? Math.min(1f, mPosition / (float) mDuration) : 0;
            drawBar(canvas, barLeft, barTop, barRight, f);
            mSmall.setColor(TEXT);
            float tb = barTop + BAR_H + 28;
            canvas.drawText(time(mPosition), barLeft, tb, mSmall);
            String left = "-" + time(Math.max(0, mDuration - mPosition));
            canvas.drawText(left, barRight - mSmall.measureText(left), tb, mSmall);
        }
    }

    /** A skip button: two triangles and a bar (next: pointing right). Selected: the blue bar. */
    private void drawSkipButton(Canvas canvas, float left, float top, boolean next, boolean sel) {
        mRect.set(left, top, left + SKIP_W, top + SKIP_H);
        if (sel) {
            mFill.setShader(new LinearGradient(0, top, 0, top + SKIP_H, SELECT_TOP, SELECT_BOTTOM,
                    Shader.TileMode.CLAMP));
        } else {
            mFill.setShader(new LinearGradient(0, top, 0, top + SKIP_H, TITLE_TOP, TITLE_BOTTOM,
                    Shader.TileMode.CLAMP));
        }
        canvas.drawRoundRect(mRect, 10, 10, mFill);
        mFill.setShader(null);
        mLine.setColor(sel ? SELECT_BOTTOM : TRACK_LINE);
        canvas.drawRoundRect(mRect, 10, 10, mLine);
        // The sign: 2 triangles of 16 x 20 px and a bar of 4 px, centered.
        float cx = mRect.centerX();
        float cy = mRect.centerY();
        float d = next ? 1 : -1;
        mFill.setColor(sel ? SELECT_TEXT : TEXT);
        for (int k = 0; k < 2; k++) {
            float base = cx - d * 18 + d * 16 * k;
            mPath.reset();
            mPath.moveTo(base, cy - 10);
            mPath.lineTo(base + d * 16, cy);
            mPath.lineTo(base, cy + 10);
            mPath.close();
            canvas.drawPath(mPath, mFill);
        }
        float bar = cx + d * 14;
        canvas.drawRect(Math.min(bar, bar + d * 4), cy - 10, Math.max(bar, bar + d * 4), cy + 10,
                mFill);
    }

    private void drawBar(Canvas canvas, float left, float top, float right, float f) {
        mRect.set(left, top, right, top + BAR_H);
        mFill.setColor(TRACK);
        canvas.drawRoundRect(mRect, BAR_H / 2f, BAR_H / 2f, mFill);
        if (f > 0) {
            RectF fill = new RectF(left, top, left + Math.max(BAR_H, (right - left) * f),
                    top + BAR_H);
            mFill.setShader(new LinearGradient(0, top, 0, top + BAR_H, SELECT_TOP, SELECT_BOTTOM,
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(fill, BAR_H / 2f, BAR_H / 2f, mFill);
            mFill.setShader(null);
        }
        mLine.setColor(TRACK_LINE);
        canvas.drawRoundRect(mRect, BAR_H / 2f, BAR_H / 2f, mLine);
    }

    private void drawSpeaker(Canvas canvas, float x, float cy, boolean loud) {
        mFill.setColor(TEXT_GRAY);
        canvas.drawRect(x, cy - 4, x + 5, cy + 4, mFill);
        mPath.reset();
        mPath.moveTo(x + 5, cy - 4);
        mPath.lineTo(x + 12, cy - 9);
        mPath.lineTo(x + 12, cy + 9);
        mPath.lineTo(x + 5, cy + 4);
        mPath.close();
        canvas.drawPath(mPath, mFill);
        mLine.setColor(TEXT_GRAY);
        mLine.setStrokeWidth(2);
        for (int i = 0; i < (loud ? 2 : 0); i++) {
            float r = 6 + 5 * i;
            mRect.set(x + 12 - r, cy - r, x + 12 + r, cy + r);
            canvas.drawArc(mRect, -45, 90, false, mLine);
        }
        mLine.setStrokeWidth(1);
    }

    /** A simple music note: the placeholder for a song without cover art. */
    private void drawNote(Canvas canvas, float cx, float cy, float size) {
        mFill.setColor(NOTE);
        float r = size * 0.22f;
        canvas.drawOval(cx - size * 0.35f - r, cy + size * 0.28f - r * 0.75f,
                cx - size * 0.35f + r, cy + size * 0.28f + r * 0.75f, mFill);
        canvas.drawRect(cx - size * 0.35f + r - 5, cy - size * 0.5f, cx - size * 0.35f + r,
                cy + size * 0.28f, mFill);
        mPath.reset();
        mPath.moveTo(cx - size * 0.35f + r - 5, cy - size * 0.5f);
        mPath.lineTo(cx + size * 0.3f, cy - size * 0.32f);
        mPath.lineTo(cx + size * 0.3f, cy - size * 0.18f);
        mPath.lineTo(cx - size * 0.35f + r - 5, cy - size * 0.36f);
        mPath.close();
        canvas.drawPath(mPath, mFill);
    }

    private void drawCropped(Canvas canvas, Bitmap b, RectF dst) {
        float scale = Math.max(dst.width() / b.getWidth(), dst.height() / b.getHeight());
        int sw = Math.round(dst.width() / scale);
        int sh = Math.round(dst.height() / scale);
        int sx = (b.getWidth() - sw) / 2;
        int sy = (b.getHeight() - sh) / 2;
        mSrc.set(sx, sy, sx + sw, sy + sh);
        canvas.drawBitmap(b, mSrc, dst, mArtPaint);
    }

    private static String time(int ms) {
        int s = ms / 1000;
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    private static String ellipsize(String s, Paint p, float width) {
        if (s == null) {
            return "";
        }
        if (p.measureText(s) <= width) {
            return s;
        }
        int n = p.breakText(s, true, Math.max(0, width - p.measureText("…")), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }

    // Touch: a tap on a row selects and presses it.
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && !mNowPlaying && mMenu != null) {
            int listW = mMenu.split && landscape() ? mSw / 2 : mSw;
            float x = e.getX() - BEZEL;
            float y = e.getY() - BEZEL;
            if (x >= 0 && x < listW && y > TITLE_H && y < listBottom()) {
                int i = mMenu.top + (int) ((y - TITLE_H) / ROW_H);
                if (i < mMenu.items.size()) {
                    mListener.onRowTapped(i);
                }
            }
            return true;
        }
        return true;
    }
}
