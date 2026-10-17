/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.home;

import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.List;

/**
 * PlayThing home screen (docs/home-screen.md):
 * CarPlay (DiPlay), Bluetooth settings, and Settings as three tiles, and the Bluetooth audio
 * device at the top right. The knob moves the focus (DPAD left and right) and opens the
 * focused tile (ENTER); touch also works.
 */
public class HomeActivity extends Activity implements HomeView.Listener {
    private static final String TAG = "CptHome";
    private static final ComponentName DIPLAY = new ComponentName(
            "com.shihab.diplay", "com.shilapi.xcertplay.DiPlayActivity");
    private static final int BG = 0xFF161826;
    // One knob detent moves the focus by one tile: ignore knob events that come less than
    // 180 ms after the last one (design: "Input").
    private static final long KNOB_GAP_MS = 180;

    private HomeView mHome;
    private BluetoothA2dp mA2dp;
    private long mLastKnob = -KNOB_GAP_MS;
    private boolean mEnterDown;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateStatus();
        }
    };

    private final BluetoothProfile.ServiceListener mA2dpListener =
            new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mA2dp = (BluetoothA2dp) proxy;
            updateStatus();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            mA2dp = null;
            updateStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHome = new HomeView(this, this);
        setContentView(mHome);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(this, mA2dpListener, BluetoothProfile.A2DP);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(mReceiver, filter);
    }

    // The wallpaper is in memory only while the home screen is visible: RGB_565, 800 x 480,
    // 750 KB (the PNG has RGB_565 levels with a dither, scripts/rgb565-dither.py). It is the
    // window background, so a focus change does not draw it again.
    @Override
    protected void onStart() {
        super.onStart();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inScaled = false;
        Bitmap wallpaper = BitmapFactory.decodeResource(getResources(), R.drawable.wallpaper, o);
        if (wallpaper != null) {
            getWindow().setBackgroundDrawable(new BitmapDrawable(getResources(), wallpaper));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onPause() {
        mEnterDown = false;
        mHome.setTilePressed(mHome.getFocusedTile(), false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        // The app is in front: remove the toast, and release the wallpaper.
        mHome.hideToast();
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mReceiver);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && mA2dp != null) {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, mA2dp);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // The home screen is the bottom of the stack.
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
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        mEnterDown = true;
                        mHome.setTilePressed(mHome.getFocusedTile(), true);
                    }
                } else if (mEnterDown) {
                    mEnterDown = false;
                    mHome.setTilePressed(mHome.getFocusedTile(), false);
                    onLaunch(mHome.getFocusedTile());
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

    /** Clockwise (+1) moves the focus right. No wrap at the ends. */
    private void knob(int step, long time) {
        if (time - mLastKnob < KNOB_GAP_MS) {
            return;
        }
        mLastKnob = time;
        mEnterDown = false;
        mHome.setFocusedTile(mHome.getFocusedTile() + step);
    }

    @Override
    public void onLaunch(int tile) {
        Intent intent;
        int label;
        switch (tile) {
            case 0:
                // The launcher intent: with it, DiPlay opens the running CarPlay session
                // (DiPlayActivity.isLauncherIntent). With a plain component intent it shows its
                // menu, and "Connect with USB" then hangs.
                intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(DIPLAY);
                label = R.string.carplay;
                break;
            case 1:
                intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                label = R.string.bluetooth;
                break;
            case 2:
                intent = new Intent(Settings.ACTION_SETTINGS);
                label = R.string.settings;
                break;
            default:
                return;
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            mHome.showToast(getString(R.string.opening, getString(label)));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no activity for " + intent, e);
        }
    }

    private void updateStatus() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            mHome.setStatus(getString(R.string.bt_off), false);
            return;
        }
        List<BluetoothDevice> devices = mA2dp != null ? mA2dp.getConnectedDevices() : null;
        if (devices == null || devices.isEmpty()) {
            mHome.setStatus(getString(R.string.bt_none), false);
            return;
        }
        String name = devices.get(0).getName();
        mHome.setStatus(name != null ? name : devices.get(0).getAddress(), true);
    }
}
