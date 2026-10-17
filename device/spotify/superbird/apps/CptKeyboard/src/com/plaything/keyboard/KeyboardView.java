/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The keys of the PlayThing keyboard, drawn in one view (docs/keyboard.md). Three layouts:
 * a number row for number fields, QWERTY letters, and digits with symbols. Colors from the
 * home screen. Sizes are pixels (800 x 480 screen, density 240), as in CptHome.
 *
 * A preview bar above the keys shows the field's text and cursor (KeyboardService reads them):
 * on the 480 px screen the keyboard often covers the field.
 *
 * Touch: a key types when the finger lifts on it; backspace types at once and repeats while held.
 * Knob: {@link #moveHighlight} and {@link #pressHighlighted} (KeyboardService).
 */
final class KeyboardView extends View {
    interface Listener {
        void onKey(Key key);
    }

    static final int CHAR = 0;
    static final int BACKSPACE = 1;
    static final int SHIFT = 2;
    static final int SPACE = 3;
    static final int DONE = 4;
    static final int SYMBOLS = 5;
    static final int LETTERS = 6;

    static final int LAYOUT_NUMBER = 0;
    static final int LAYOUT_LETTERS = 1;
    static final int LAYOUT_SYMBOLS = 2;

    static final int SHIFT_OFF = 0;
    static final int SHIFT_ONCE = 1;
    static final int SHIFT_LOCK = 2;

    static final class Key {
        final int type;
        final String text;
        final float units;
        final RectF rect = new RectF();

        Key(int type, String text, float units) {
            this.type = type;
            this.text = text;
            this.units = units;
        }
    }

    private static final int PAD = 6;
    private static final int GAP = 5;
    private static final int KEY_H_NUMBER = 56;
    private static final int KEY_H_TEXT = 42;
    private static final int PREVIEW_H = 38;
    private static final int PREVIEW_PAD_X = 14;
    private static final float RADIUS = 8;
    private static final int ICON = 26;
    private static final long REPEAT_START_MS = 400;
    private static final long REPEAT_MS = 60;

    private static final int BG = 0xFF161826;
    private static final int PREVIEW_BG = 0xFF1C1E2B;
    private static final int TEXT_MUTED = 0xFF9397AB;
    private static final int KEY = 0xFF2A2C3A;
    private static final int KEY_SPECIAL = 0xFF1F2130;
    private static final int KEY_PRESSED = 0x3334C759;
    private static final int TEXT = 0xFFE9E9ED;
    private static final int ICON_MUTED = 0xFFB2B6CA;
    private static final int ACCENT = 0xFF34C759;
    private static final int ACCENT_LIGHT = 0xFFB4F0C3;

    private final Listener mListener;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSmallLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable mBackspace;
    private final Drawable mShift;
    private final Drawable mShiftLock;
    private final Drawable mCheck;
    private final Paint mPreview = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTmp = new RectF();
    private final Runnable mRepeat = new Runnable() {
        @Override
        public void run() {
            if (mPressed != null && mPressed.type == BACKSPACE) {
                mListener.onKey(mPressed);
                postDelayed(this, REPEAT_MS);
            }
        }
    };

    private List<List<Key>> mRows = new ArrayList<>();
    private final List<Key> mKeys = new ArrayList<>();
    private int mLayout = -1;
    private int mShiftState = SHIFT_OFF;
    private int mHighlight = -1;
    private Key mPressed;
    private String mText = "";
    private int mCursor;
    private String mHint = "";

    KeyboardView(Context context, Listener listener) {
        super(context);
        mListener = listener;
        mBackspace = context.getDrawable(R.drawable.ic_backspace).mutate();
        mShift = context.getDrawable(R.drawable.ic_shift).mutate();
        mShiftLock = context.getDrawable(R.drawable.ic_shift_lock).mutate();
        mCheck = context.getDrawable(R.drawable.ic_check).mutate();
        mLabel.setTypeface(Typeface.create(Typeface.SANS_SERIF, 500, false));
        mLabel.setTextAlign(Paint.Align.CENTER);
        mSmallLabel.setTypeface(Typeface.create(Typeface.SANS_SERIF, 500, false));
        mSmallLabel.setTextAlign(Paint.Align.CENTER);
        mSmallLabel.setTextSize(17);
        mBorder.setStyle(Paint.Style.STROKE);
        mPreview.setTypeface(Typeface.create(Typeface.SANS_SERIF, 400, false));
        mPreview.setTextSize(19);
        setBackgroundColor(BG);
        setLayout(LAYOUT_LETTERS);
    }

    int getLayout() {
        return mLayout;
    }

    int getShiftState() {
        return mShiftState;
    }

    void setShiftState(int state) {
        mShiftState = state;
        invalidate();
    }

    void setLayout(int layout) {
        if (layout == mLayout) {
            return;
        }
        mLayout = layout;
        mRows = build(layout);
        mKeys.clear();
        for (List<Key> row : mRows) {
            mKeys.addAll(row);
        }
        if (mHighlight >= mKeys.size()) {
            mHighlight = mKeys.size() - 1;
        }
        requestLayout();
        place(getWidth());
        invalidate();
    }

    /** The field's text around the cursor, and its hint for an empty field. */
    void setPreview(String text, int cursor, String hint) {
        mText = text;
        mCursor = Math.max(0, Math.min(cursor, text.length()));
        mHint = hint;
        invalidate();
    }

    void clearHighlight() {
        mHighlight = -1;
        invalidate();
    }

    /** Knob: +1 next key, -1 previous key, in reading order; it wraps at the ends. */
    void moveHighlight(int step) {
        int n = mKeys.size();
        if (mHighlight < 0) {
            mHighlight = step > 0 ? 0 : n - 1;
        } else {
            mHighlight = ((mHighlight + step) % n + n) % n;
        }
        invalidate();
    }

    /** Knob press: types the highlighted key; the first press only shows the highlight. */
    void pressHighlighted() {
        if (mHighlight < 0) {
            mHighlight = 0;
            invalidate();
            return;
        }
        mListener.onKey(mKeys.get(mHighlight));
    }

    private static List<List<Key>> build(int layout) {
        List<List<Key>> rows = new ArrayList<>();
        if (layout == LAYOUT_NUMBER) {
            List<Key> row = chars("1234567890");
            row.add(new Key(BACKSPACE, null, 1));
            row.add(new Key(DONE, null, 1));
            rows.add(row);
            return rows;
        }
        if (layout == LAYOUT_LETTERS) {
            rows.add(chars("qwertyuiop"));
            rows.add(chars("asdfghjkl"));
            List<Key> row3 = new ArrayList<>();
            row3.add(new Key(SHIFT, null, 1.5f));
            row3.addAll(chars("zxcvbnm"));
            row3.add(new Key(BACKSPACE, null, 1.5f));
            rows.add(row3);
            rows.add(bottomRow(new Key(SYMBOLS, "?123", 1.5f)));
            return rows;
        }
        rows.add(chars("1234567890"));
        rows.add(chars("@#$%&-+()/"));
        List<Key> row3 = chars("*\"':;!?_");
        row3.add(new Key(BACKSPACE, null, 2));
        rows.add(row3);
        rows.add(bottomRow(new Key(LETTERS, "ABC", 1.5f)));
        return rows;
    }

    private static List<Key> bottomRow(Key modeKey) {
        List<Key> row = new ArrayList<>();
        row.add(modeKey);
        row.add(new Key(CHAR, ",", 1));
        row.add(new Key(SPACE, " ", 5));
        row.add(new Key(CHAR, ".", 1));
        row.add(new Key(DONE, null, 1.5f));
        return row;
    }

    private static List<Key> chars(String s) {
        List<Key> row = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            row.add(new Key(CHAR, String.valueOf(s.charAt(i)), 1));
        }
        return row;
    }

    private int unitsPerRow() {
        return mLayout == LAYOUT_NUMBER ? 12 : 10;
    }

    private int keyHeight() {
        return mLayout == LAYOUT_NUMBER ? KEY_H_NUMBER : KEY_H_TEXT;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int rows = mRows.size();
        setMeasuredDimension(width,
                PREVIEW_H + 2 * PAD + rows * keyHeight() + (rows - 1) * GAP);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        place(w);
    }

    /** Key rectangles: rows of up to unitsPerRow() units, shorter rows centered. */
    private void place(int width) {
        if (width <= 0) {
            return;
        }
        int n = unitsPerRow();
        float unit = (width - 2f * PAD - (n - 1) * GAP) / n;
        float top = PREVIEW_H + PAD;
        for (List<Key> row : mRows) {
            float units = 0;
            for (Key k : row) {
                units += k.units;
            }
            float x = PAD + (n - units) / 2f * (unit + GAP);
            for (Key k : row) {
                float w = k.units * unit + (k.units - 1) * GAP;
                k.rect.set(x, top, x + w, top + keyHeight());
                x += w + GAP;
            }
            top += keyHeight() + GAP;
        }
        mLabel.setTextSize(mLayout == LAYOUT_NUMBER ? 26 : 22);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawPreview(canvas);
        for (int i = 0; i < mKeys.size(); i++) {
            Key k = mKeys.get(i);
            boolean special = k.type != CHAR && k.type != SPACE;
            mFill.setColor(k == mPressed ? KEY_PRESSED : special ? KEY_SPECIAL : KEY);
            canvas.drawRoundRect(k.rect, RADIUS, RADIUS, mFill);
            if (k.type == DONE || i == mHighlight) {
                boolean highlight = i == mHighlight;
                mBorder.setColor(ACCENT);
                mBorder.setStrokeWidth(highlight ? 2 : 1);
                float inset = highlight ? 1 : 0.5f;
                mTmp.set(k.rect.left + inset, k.rect.top + inset, k.rect.right - inset,
                        k.rect.bottom - inset);
                canvas.drawRoundRect(mTmp, RADIUS, RADIUS, mBorder);
            }
            switch (k.type) {
                case CHAR:
                    String label = mShiftState != SHIFT_OFF && mLayout == LAYOUT_LETTERS
                            ? k.text.toUpperCase() : k.text;
                    drawText(canvas, k, label, mLabel, TEXT);
                    break;
                case SYMBOLS:
                case LETTERS:
                    drawText(canvas, k, k.text, mSmallLabel, ICON_MUTED);
                    break;
                case BACKSPACE:
                    drawIcon(canvas, k, mBackspace, ICON_MUTED);
                    break;
                case SHIFT:
                    drawIcon(canvas, k, mShiftState == SHIFT_LOCK ? mShiftLock : mShift,
                            mShiftState == SHIFT_OFF ? ICON_MUTED : ACCENT_LIGHT);
                    break;
                case DONE:
                    drawIcon(canvas, k, mCheck, ACCENT_LIGHT);
                    break;
            }
        }
    }

    /** One line: the text scrolls so that the cursor stays visible; a green bar marks it. */
    private void drawPreview(Canvas canvas) {
        int w = getWidth();
        mFill.setColor(PREVIEW_BG);
        canvas.drawRect(0, 0, w, PREVIEW_H, mFill);
        float baseline = PREVIEW_H / 2f - (mPreview.ascent() + mPreview.descent()) / 2;
        float left = PREVIEW_PAD_X;
        float right = w - PREVIEW_PAD_X;
        if (mText.isEmpty()) {
            mPreview.setColor(TEXT_MUTED);
            canvas.drawText(mHint, left, baseline, mPreview);
            mFill.setColor(ACCENT);
            canvas.drawRect(left, 8, left + 2, PREVIEW_H - 8, mFill);
            return;
        }
        float before = mPreview.measureText(mText, 0, mCursor);
        float x = left;
        if (left + before > right - 4) {
            x = right - 4 - before;
        }
        canvas.save();
        canvas.clipRect(left, 0, right, PREVIEW_H);
        mPreview.setColor(TEXT);
        canvas.drawText(mText, x, baseline, mPreview);
        mFill.setColor(ACCENT);
        canvas.drawRect(x + before, 8, x + before + 2, PREVIEW_H - 8, mFill);
        canvas.restore();
    }

    private void drawText(Canvas canvas, Key k, String text, Paint paint, int color) {
        paint.setColor(color);
        float y = k.rect.centerY() - (paint.ascent() + paint.descent()) / 2;
        canvas.drawText(text, k.rect.centerX(), y, paint);
    }

    private void drawIcon(Canvas canvas, Key k, Drawable d, int color) {
        int cx = (int) k.rect.centerX();
        int cy = (int) k.rect.centerY();
        d.setBounds(cx - ICON / 2, cy - ICON / 2, cx + ICON / 2, cy + ICON / 2);
        d.setTint(color);
        d.draw(canvas);
    }

    private Key keyAt(float x, float y) {
        for (Key k : mKeys) {
            // The gaps belong to the nearest key, so a touch between keys still types.
            if (x >= k.rect.left - GAP / 2f && x <= k.rect.right + GAP / 2f
                    && y >= k.rect.top - GAP / 2f && y <= k.rect.bottom + GAP / 2f) {
                return k;
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mPressed = keyAt(e.getX(), e.getY());
                if (mHighlight >= 0) {
                    mHighlight = -1;
                }
                if (mPressed != null && mPressed.type == BACKSPACE) {
                    mListener.onKey(mPressed);
                    postDelayed(mRepeat, REPEAT_START_MS);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mPressed != null && keyAt(e.getX(), e.getY()) != mPressed) {
                    release();
                }
                return true;
            case MotionEvent.ACTION_UP:
                Key key = mPressed;
                release();
                if (key != null && key.type != BACKSPACE && keyAt(e.getX(), e.getY()) == key) {
                    mListener.onKey(key);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void release() {
        removeCallbacks(mRepeat);
        mPressed = null;
        invalidate();
    }
}
