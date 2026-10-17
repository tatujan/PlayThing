/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.keyboard;

import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * PlayThing keyboard (docs/keyboard.md): the only input method in the image. Number fields
 * (for example the Bluetooth PIN of a car stereo) get one row of digits; other fields get QWERTY.
 *
 * Knob: while the keyboard shows, DPAD_LEFT/RIGHT move a highlight over the keys and ENTER types
 * the highlighted key; an input method gets key events before the app. BACK closes the keyboard
 * (InputMethodService). Done runs the field's action, or closes the keyboard.
 *
 * The preview bar shows the field's text around the cursor (up to PREVIEW_CHARS on each side),
 * because the keyboard can cover the field; password fields show dots.
 */
public class KeyboardService extends InputMethodService implements KeyboardView.Listener {
    private static final int PREVIEW_CHARS = 120;

    private KeyboardView mView;
    private boolean mEnterDown;
    private boolean mPassword;
    private String mHint = "";

    @Override
    public View onCreateInputView() {
        mView = new KeyboardView(this, this);
        return mView;
    }

    // The screen is 800 x 480 landscape: no full-screen text box, the field stays visible above.
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (mView == null) {
            return;
        }
        switch (info.inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
            case InputType.TYPE_CLASS_PHONE:
            case InputType.TYPE_CLASS_DATETIME:
                mView.setLayout(KeyboardView.LAYOUT_NUMBER);
                break;
            default:
                mView.setLayout(KeyboardView.LAYOUT_LETTERS);
                break;
        }
        mView.setShiftState(KeyboardView.SHIFT_OFF);
        mView.clearHighlight();
        mEnterDown = false;
        mPassword = isPassword(info.inputType);
        CharSequence hint = info.hintText != null ? info.hintText : info.label;
        mHint = hint != null ? hint.toString() : "";
        refreshPreview();
    }

    // The editor reports each change of its text or cursor here.
    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart,
                candidatesEnd);
        refreshPreview();
    }

    private static boolean isPassword(int inputType) {
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        switch (inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_TEXT:
                return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
            case InputType.TYPE_CLASS_NUMBER:
                return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
        return false;
    }

    private void refreshPreview() {
        InputConnection ic = getCurrentInputConnection();
        if (mView == null || ic == null) {
            return;
        }
        CharSequence before = ic.getTextBeforeCursor(PREVIEW_CHARS, 0);
        CharSequence after = ic.getTextAfterCursor(PREVIEW_CHARS, 0);
        String b = before != null ? before.toString() : "";
        String a = after != null ? after.toString() : "";
        String text = (b + a).replace('\n', ' ');
        if (mPassword) {
            text = new String(new char[text.length()]).replace('\0', '\u2022');
        }
        mView.setPreview(text, b.length(), mHint);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isInputViewShown() && mView != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    mView.moveHighlight(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    mView.moveHighlight(1);
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (event.getRepeatCount() == 0) {
                        mEnterDown = true;
                    }
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isInputViewShown() && mView != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (mEnterDown) {
                        mEnterDown = false;
                        mView.pressHighlighted();
                        return true;
                    }
                    break;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onKey(KeyboardView.Key key) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        switch (key.type) {
            case KeyboardView.CHAR:
                String text = key.text;
                if (mView.getLayout() == KeyboardView.LAYOUT_LETTERS
                        && mView.getShiftState() != KeyboardView.SHIFT_OFF) {
                    text = text.toUpperCase();
                    if (mView.getShiftState() == KeyboardView.SHIFT_ONCE) {
                        mView.setShiftState(KeyboardView.SHIFT_OFF);
                    }
                }
                ic.commitText(text, 1);
                break;
            case KeyboardView.SPACE:
                ic.commitText(" ", 1);
                break;
            case KeyboardView.BACKSPACE:
                if (TextUtils.isEmpty(ic.getSelectedText(0))) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                } else {
                    ic.commitText("", 1);
                }
                break;
            case KeyboardView.SHIFT:
                // Off -> once -> locked -> off.
                mView.setShiftState((mView.getShiftState() + 1) % 3);
                break;
            case KeyboardView.SYMBOLS:
                mView.setLayout(KeyboardView.LAYOUT_SYMBOLS);
                break;
            case KeyboardView.LETTERS:
                mView.setLayout(KeyboardView.LAYOUT_LETTERS);
                break;
            case KeyboardView.DONE:
                done(ic);
                break;
        }
    }

    private void done(InputConnection ic) {
        EditorInfo info = getCurrentInputEditorInfo();
        int action = info != null ? info.imeOptions & EditorInfo.IME_MASK_ACTION
                : EditorInfo.IME_ACTION_NONE;
        boolean noEnterAction = info != null
                && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE
                && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
            if (action == EditorInfo.IME_ACTION_DONE) {
                requestHideSelf(0);
            }
        } else if (info != null && (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
            ic.commitText("\n", 1);
        } else {
            requestHideSelf(0);
        }
    }
}
