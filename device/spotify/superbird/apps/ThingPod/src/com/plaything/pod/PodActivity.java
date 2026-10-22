/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.pod;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ThingPod (docs/thingpod.md): an iPod classic style music player for the Car Thing. The knob is
 * the click wheel: it moves the selection (on Now Playing: the volume); the knob press selects
 * (on Now Playing: play or pause; press and hold: the skip buttons Previous and Next in place of
 * the progress bar); Back is the Menu button. Preset 1 to 3 (previous, play/pause,
 * next) go to the player through its media session. Touch also works.
 */
public class PodActivity extends Activity implements PodView.Listener, PlayerService.Listener {
    // One knob detent moves the selection by one row, as on the home screen.
    private static final long KNOB_GAP_MS = 120;
    private static final long TICK_MS = 500;
    private static final int ART_SIZE = 440;
    private static final int REQUEST_STORAGE = 1;
    // Knob press and hold on Now Playing shows the skip buttons; they go after 4 s without input.
    private static final long HOLD_MS = 500;
    private static final long SKIP_MS = 4000;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final Deque<PodView.Menu> mStack = new ArrayDeque<>();
    private PodView mView;
    private PlayerService mPlayer;
    private Library mLibrary;
    private AudioManager mAudio;
    private long mLastKnob = -KNOB_GAP_MS;
    private boolean mEnterDown;
    private boolean mHeld;
    private long mArtAlbum = -1;
    private int mLoadId;

    private final Runnable mHold = () -> {
        mHeld = true;
        showSkip();
    };
    private final Runnable mHideSkip = () -> mView.hideSkip();

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updatePlayback();
            mMain.postDelayed(this, TICK_MS);
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mPlayer = ((PlayerService.LocalBinder) binder).service();
            mPlayer.addListener(PodActivity.this);
            if (mLibrary != null) {
                mPlayer.setLibrary(mLibrary);
            }
            // Music plays already (the activity started again): the main menu gets "Now Playing".
            if (mStack.size() == 1 && mPlayer.current() != null) {
                PodView.Menu main = mainMenu();
                mStack.clear();
                mStack.push(main);
                if (!mView.nowPlaying()) {
                    mView.showMenu(main);
                }
            }
            onPlayerChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mPlayer = null;
        }
    };

    // The media scanner found new music (for example on a USB stick).
    private final BroadcastReceiver mScanner = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadLibrary();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAudio = getSystemService(AudioManager.class);
        mView = new PodView(this, this);
        setContentView(mView);
        mStack.push(mainMenu());
        mView.showMenu(mStack.peek());
        bindService(new Intent(this, PlayerService.class), mConnection, BIND_AUTO_CREATE);
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
        } else {
            loadLibrary();
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        filter.addDataScheme("file");
        registerReceiver(mScanner, filter);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        loadLibrary();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mMain.post(mTick);
    }

    @Override
    protected void onStop() {
        mMain.removeCallbacks(mTick);
        mEnterDown = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mScanner);
        if (mPlayer != null) {
            mPlayer.removeListener(this);
        }
        unbindService(mConnection);
        mWorker.shutdownNow();
        super.onDestroy();
    }

    private void loadLibrary() {
        int id = ++mLoadId;
        mWorker.execute(() -> {
            Library lib = Library.load(this);
            mMain.post(() -> {
                if (id != mLoadId || isDestroyed()) {
                    return;
                }
                mLibrary = lib;
                if (mPlayer != null) {
                    mPlayer.setLibrary(lib);
                }
                // The menus show the new library from the top again.
                mStack.clear();
                mStack.push(mainMenu());
                if (!mView.nowPlaying()) {
                    mView.showMenu(mStack.peek());
                }
                updateArt();
            });
        });
    }

    // ------------------------------------------------------------------ menus

    private PodView.Menu mainMenu() {
        PodView.Menu m = new PodView.Menu(getString(R.string.app_name), true);
        m.add(getString(R.string.music), true, null, () -> open(musicMenu()));
        m.add(getString(R.string.shuffle_songs), false, null, this::shuffleAll);
        if (mPlayer != null && mPlayer.current() != null) {
            m.add(getString(R.string.now_playing), true, null, this::showNowPlaying);
        }
        m.add(getString(R.string.settings), true, null, () -> open(settingsMenu()));
        return m;
    }

    private PodView.Menu musicMenu() {
        PodView.Menu m = new PodView.Menu(getString(R.string.music), true);
        if (mLibrary == null) {
            m.add(getString(R.string.loading), false, null, null);
            return m;
        }
        if (mLibrary.songs.isEmpty()) {
            m.add(getString(R.string.no_music), false, null, null);
            m.add(getString(R.string.no_music_hint), false, null, null);
            return m;
        }
        m.add(getString(R.string.artists), true, null, () -> open(artistsMenu()));
        m.add(getString(R.string.albums), true, null, () -> open(albumsMenu(mLibrary.albums,
                getString(R.string.albums))));
        m.add(getString(R.string.songs), true, null, () -> open(songsMenu(mLibrary.songs,
                getString(R.string.songs))));
        return m;
    }

    private PodView.Menu artistsMenu() {
        PodView.Menu m = new PodView.Menu(getString(R.string.artists), false);
        for (Library.Artist a : mLibrary.artists) {
            m.add(a.name, true, null, () -> open(artistMenu(a)));
        }
        return m;
    }

    private PodView.Menu artistMenu(Library.Artist artist) {
        PodView.Menu m = new PodView.Menu(artist.name, false);
        m.add(getString(R.string.all_songs), true, null,
                () -> open(songsMenu(artist.songs(), artist.name)));
        for (Library.Album a : artist.albums) {
            m.add(a.name, true, null, () -> open(songsMenu(a.songs, a.name)));
        }
        return m;
    }

    private PodView.Menu albumsMenu(List<Library.Album> albums, String title) {
        PodView.Menu m = new PodView.Menu(title, false);
        for (Library.Album a : albums) {
            m.add(a.name, true, null, () -> open(songsMenu(a.songs, a.name)));
        }
        return m;
    }

    private PodView.Menu songsMenu(List<Library.Song> songs, String title) {
        PodView.Menu m = new PodView.Menu(title, false);
        for (int i = 0; i < songs.size(); i++) {
            int index = i;
            Library.Song s = songs.get(i);
            m.add(s.title, false, null, () -> play(songs, index, false));
        }
        return m;
    }

    private PodView.Menu settingsMenu() {
        PodView.Menu m = new PodView.Menu(getString(R.string.settings), true);
        boolean shuffle = mPlayer != null && mPlayer.shuffle();
        int repeat = mPlayer != null ? mPlayer.repeat() : PlayerService.REPEAT_OFF;
        m.add(getString(R.string.shuffle), false, getString(shuffle ? R.string.on : R.string.off),
                () -> {
                    if (mPlayer != null) {
                        mPlayer.setShuffle(!mPlayer.shuffle());
                    }
                    replaceTop(settingsMenu());
                });
        m.add(getString(R.string.repeat), false, getString(repeat == PlayerService.REPEAT_ONE
                ? R.string.one : repeat == PlayerService.REPEAT_ALL ? R.string.all : R.string.off),
                () -> {
                    if (mPlayer != null) {
                        mPlayer.setRepeat((mPlayer.repeat() + 1) % 3);
                    }
                    replaceTop(settingsMenu());
                });
        if (mLibrary != null) {
            m.add(getString(R.string.about_songs, mLibrary.songs.size()), false, null, null);
        }
        return m;
    }

    private void open(PodView.Menu menu) {
        mStack.push(menu);
        mView.showMenu(menu);
        updateArt();
    }

    private void replaceTop(PodView.Menu menu) {
        PodView.Menu old = mStack.pop();
        menu.selected = old.selected;
        menu.top = old.top;
        mStack.push(menu);
        mView.showMenu(menu);
    }

    /** The skip buttons of Now Playing (Previous, Next), in place of the progress bar. */
    private void showSkip() {
        if (!mView.nowPlaying() || mPlayer == null || mPlayer.current() == null) {
            return;
        }
        mView.showSkip(PodView.SKIP_NEXT);
        keepSkip();
    }

    /** The skip buttons stay 4 s after the last input. */
    private void keepSkip() {
        mMain.removeCallbacks(mHideSkip);
        mMain.postDelayed(mHideSkip, SKIP_MS);
    }

    private void back() {
        if (mView.skip() >= 0) {
            mMain.removeCallbacks(mHideSkip);
            mView.hideSkip();
            return;
        }
        if (mView.nowPlaying()) {
            mView.showMenu(mStack.peek());
        } else if (mStack.size() > 1) {
            mStack.pop();
            mView.showMenu(mStack.peek());
        } else {
            // The main menu: leave ThingPod; the music plays on.
            moveTaskToBack(true);
            return;
        }
        updateArt();
    }

    // ------------------------------------------------------------------ playing

    private void play(List<Library.Song> songs, int index, boolean shuffle) {
        if (mPlayer == null) {
            return;
        }
        mPlayer.setLibrary(mLibrary);
        mPlayer.playQueue(new ArrayList<>(songs), index, shuffle);
        showNowPlaying();
    }

    private void shuffleAll() {
        if (mLibrary != null && !mLibrary.songs.isEmpty()) {
            play(mLibrary.songs, -1, true);
        }
    }

    private void showNowPlaying() {
        if (mPlayer == null || mPlayer.current() == null) {
            return;
        }
        // The main menu gets its "Now Playing" item.
        if (mStack.size() == 1) {
            PodView.Menu old = mStack.pop();
            PodView.Menu main = mainMenu();
            main.selected = Math.min(old.selected, main.items.size() - 1);
            mStack.push(main);
        }
        mView.showNowPlaying();
        onPlayerChanged();
    }

    @Override
    public void onPlayerChanged() {
        if (mPlayer == null) {
            return;
        }
        Library.Song s = mPlayer.current();
        if (s != null) {
            mView.setSong(s.title, s.artist, s.album,
                    getString(R.string.index, mPlayer.index() + 1, mPlayer.queueSize()));
        }
        updatePlayback();
        updateArt();
    }

    private void updatePlayback() {
        if (mPlayer != null) {
            mView.setPlayback(mPlayer.playing(), mPlayer.position(), mPlayer.duration());
        }
    }

    /** The cover: of the current song, else of the first album. Decoded on the worker. */
    private void updateArt() {
        if (mLibrary == null) {
            return;
        }
        Library.Song s = mPlayer != null ? mPlayer.current() : null;
        long album = s != null ? s.albumId
                : !mLibrary.albums.isEmpty() ? mLibrary.albums.get(0).id : -1;
        if (album == mArtAlbum) {
            return;
        }
        mArtAlbum = album;
        if (album < 0) {
            mView.setArt(null);
            return;
        }
        Library lib = mLibrary;
        mWorker.execute(() -> {
            Bitmap b = lib.art(this, album, ART_SIZE);
            mMain.post(() -> {
                if (album == mArtAlbum) {
                    mView.setArt(b);
                }
            });
        });
    }

    // ------------------------------------------------------------------ input

    @Override
    public void onRowTapped(int index) {
        PodView.Menu m = mView.menu();
        if (m != null && index < m.items.size()) {
            m.selected = index;
            press();
        }
    }

    private void press() {
        // The skip buttons stay, so that more presses skip more songs.
        int skip = mView.skip();
        if (skip >= 0) {
            if (mPlayer != null) {
                if (skip == PodView.SKIP_PREVIOUS) {
                    mPlayer.previousSong();
                } else {
                    mPlayer.next();
                }
            }
            keepSkip();
            return;
        }
        if (mView.nowPlaying()) {
            if (mPlayer != null) {
                mPlayer.playPause();
            }
            return;
        }
        PodView.Menu m = mView.menu();
        if (m != null && m.selected < m.actions.size()) {
            Runnable action = m.actions.get(m.selected);
            if (action != null) {
                action.run();
            }
        }
    }

    @Override
    public void onBackPressed() {
        back();
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
                    wheel(next ? 1 : -1, event.getEventTime());
                }
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // A short press selects (Now Playing: play or pause); press and hold on Now
                // Playing shows the skip buttons.
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        mEnterDown = true;
                        mHeld = false;
                        if (mView.nowPlaying() && mView.skip() < 0) {
                            mMain.postDelayed(mHold, HOLD_MS);
                        }
                    }
                } else if (mEnterDown) {
                    mEnterDown = false;
                    mMain.removeCallbacks(mHold);
                    if (!mHeld) {
                        press();
                    }
                    mHeld = false;
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
                wheel(v > 0 ? 1 : -1, event.getEventTime());
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /** The knob as the click wheel: the selection, or the volume on Now Playing. */
    private void wheel(int step, long time) {
        if (time - mLastKnob < KNOB_GAP_MS) {
            return;
        }
        mLastKnob = time;
        if (mView.skip() >= 0) {
            mView.moveSkip(step);
            keepSkip();
        } else if (mView.nowPlaying()) {
            mAudio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    step > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            int max = mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int vol = mAudio.getStreamVolume(AudioManager.STREAM_MUSIC);
            mView.showVolume(max > 0 ? vol / (float) max : 0);
        } else {
            mView.move(step);
        }
    }
}
