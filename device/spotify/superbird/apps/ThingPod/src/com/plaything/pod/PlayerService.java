/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.pod;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Plays the queue in the background (docs/thingpod.md). A media session gives the title to the
 * car stereo over Bluetooth (AVRCP) and takes its controls and the Car Thing's media buttons
 * (preset 1 to 3: previous, play/pause, next). It pauses when another app takes the audio (for
 * example CarPlay) and when the Bluetooth audio device disconnects ("becoming noisy").
 */
public class PlayerService extends Service {
    interface Listener {
        void onPlayerChanged();
    }

    static final int REPEAT_OFF = 0;
    static final int REPEAT_ONE = 1;
    static final int REPEAT_ALL = 2;

    private static final String TAG = "ThingPod";
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION = 1;
    // "Previous" in the first 3 s goes to the previous song; later it starts the song again.
    private static final int RESTART_MS = 3000;

    private final IBinder mBinder = new LocalBinder();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<>();
    private final Random mRandom = new Random();
    private AudioManager mAudio;
    private AudioFocusRequest mFocus;
    private MediaSession mSession;
    private MediaPlayer mPlayer;
    private Library mLibrary;

    // The queue in play order (shuffled when shuffle is on), and the original order.
    private final List<Library.Song> mQueue = new ArrayList<>();
    private final List<Library.Song> mOrder = new ArrayList<>();
    private int mIndex = -1;
    private boolean mPrepared;
    private boolean mPlaying;
    private boolean mShuffle;
    private int mRepeat = REPEAT_OFF;
    private boolean mForeground;

    final class LocalBinder extends Binder {
        PlayerService service() {
            return PlayerService.this;
        }
    }

    private final BroadcastReceiver mNoisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            pause();
        }
    };

    private final AudioManager.OnAudioFocusChangeListener mFocusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mAudio = getSystemService(AudioManager.class);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        mFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(mFocusListener, mMain)
                .build();
        mPlayer = new MediaPlayer();
        mPlayer.setAudioAttributes(attrs);
        mPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mPlayer.setOnCompletionListener(mp -> onCompletion());
        mPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "player error " + what + "/" + extra);
            mPrepared = false;
            mPlaying = false;
            changed();
            return true;
        });
        mSession = new MediaSession(this, TAG);
        mSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }
        });
        mSession.setActive(true);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.channel), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        registerReceiver(mNoisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mNoisy);
        mAudio.abandonAudioFocusRequest(mFocus);
        mSession.release();
        mPlayer.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    void setLibrary(Library library) {
        mLibrary = library;
    }

    void addListener(Listener l) {
        mListeners.add(l);
    }

    void removeListener(Listener l) {
        mListeners.remove(l);
    }

    // ------------------------------------------------------------------ state

    Library.Song current() {
        return mIndex >= 0 && mIndex < mQueue.size() ? mQueue.get(mIndex) : null;
    }

    int index() {
        return mIndex;
    }

    int queueSize() {
        return mQueue.size();
    }

    boolean playing() {
        return mPlaying;
    }

    int position() {
        return mPrepared ? mPlayer.getCurrentPosition() : 0;
    }

    int duration() {
        return mPrepared ? mPlayer.getDuration() : 0;
    }

    boolean shuffle() {
        return mShuffle;
    }

    int repeat() {
        return mRepeat;
    }

    void setRepeat(int repeat) {
        mRepeat = repeat;
        changed();
    }

    /** Shuffle on: the rest of the queue in a random order; off: the original order. */
    void setShuffle(boolean on) {
        if (on == mShuffle) {
            return;
        }
        mShuffle = on;
        Library.Song cur = current();
        mQueue.clear();
        mQueue.addAll(mOrder);
        if (on) {
            Collections.shuffle(mQueue, mRandom);
            if (cur != null) {
                mQueue.remove(cur);
                mQueue.add(0, cur);
            }
        }
        mIndex = cur != null ? mQueue.indexOf(cur) : -1;
        changed();
    }

    // ------------------------------------------------------------------ control

    /** Plays the songs from start (the song that the user pressed). */
    void playQueue(List<Library.Song> songs, int start, boolean shuffle) {
        if (songs.isEmpty()) {
            return;
        }
        mOrder.clear();
        mOrder.addAll(songs);
        mQueue.clear();
        mQueue.addAll(songs);
        mShuffle = shuffle;
        Library.Song first = songs.get(Math.max(0, Math.min(start, songs.size() - 1)));
        if (shuffle) {
            Collections.shuffle(mQueue, mRandom);
            if (start >= 0) {
                mQueue.remove(first);
                mQueue.add(0, first);
            }
        }
        mIndex = shuffle && start < 0 ? 0 : mQueue.indexOf(first);
        load(true);
    }

    void playPause() {
        if (mPlaying) {
            pause();
        } else {
            play();
        }
    }

    void play() {
        if (current() == null) {
            return;
        }
        if (!mPrepared) {
            load(true);
            return;
        }
        if (mAudio.requestAudioFocus(mFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return;
        }
        mPlayer.start();
        mPlaying = true;
        startForegroundNotice();
        changed();
    }

    void pause() {
        if (mPrepared && mPlayer.isPlaying()) {
            mPlayer.pause();
        }
        mPlaying = false;
        stopForeground(false);
        mForeground = false;
        updateNotification();
        changed();
    }

    void next() {
        if (mQueue.isEmpty()) {
            return;
        }
        if (mIndex + 1 < mQueue.size()) {
            mIndex++;
        } else if (mRepeat == REPEAT_ALL) {
            mIndex = 0;
        } else {
            return;
        }
        load(mPlaying);
    }

    void previous() {
        if (mQueue.isEmpty()) {
            return;
        }
        if (position() > RESTART_MS || mIndex == 0) {
            seekTo(0);
            return;
        }
        mIndex--;
        load(mPlaying);
    }

    /** Always the previous song (the skip button); at the first song, its start. */
    void previousSong() {
        if (mQueue.isEmpty()) {
            return;
        }
        if (mIndex == 0) {
            seekTo(0);
            return;
        }
        mIndex--;
        load(mPlaying);
    }

    void seekTo(int ms) {
        if (mPrepared) {
            mPlayer.seekTo(Math.max(0, Math.min(ms, mPlayer.getDuration())));
            changed();
        }
    }

    private void onCompletion() {
        if (mRepeat == REPEAT_ONE) {
            seekTo(0);
            play();
        } else if (mIndex + 1 < mQueue.size() || mRepeat == REPEAT_ALL) {
            next();
        } else {
            mPlaying = false;
            stopForeground(false);
            mForeground = false;
            changed();
        }
    }

    /** Opens the current song; plays it when start is true. */
    private void load(boolean start) {
        Library.Song s = current();
        if (s == null) {
            return;
        }
        mPrepared = false;
        mPlayer.reset();
        try {
            mPlayer.setDataSource(this, s.uri());
            mPlayer.prepare();
            mPrepared = true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot open " + s.uri(), e);
            mPlaying = false;
            changed();
            return;
        }
        updateMetadata(s);
        if (start) {
            play();
        } else {
            changed();
        }
    }

    // ------------------------------------------------------------------ session and notice

    private void updateMetadata(Library.Song s) {
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, mIndex + 1)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, mQueue.size());
        Bitmap art = mLibrary != null ? mLibrary.art(this, s.albumId, 300) : null;
        if (art != null) {
            m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        }
        mSession.setMetadata(m.build());
    }

    private void updateState() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP;
        mSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(mPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position(), mPlaying ? 1f : 0f)
                .build());
    }

    private Notification notice() {
        Library.Song s = current();
        Intent open = new Intent(this, PodActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(s != null ? s.title : getString(R.string.app_name))
                .setContentText(s != null ? s.artist : "")
                .setContentIntent(PendingIntent.getActivity(this, 0, open, 0))
                .setOngoing(mPlaying)
                .setStyle(new Notification.MediaStyle().setMediaSession(mSession.getSessionToken()))
                .build();
    }

    private void startForegroundNotice() {
        if (!mForeground) {
            // The service runs on while the music plays, also without the screen.
            startService(new Intent(this, PlayerService.class));
            startForeground(NOTIFICATION, notice());
            mForeground = true;
        } else {
            updateNotification();
        }
    }

    private void updateNotification() {
        if (current() != null) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notice());
        }
    }

    private void changed() {
        updateState();
        for (Listener l : new ArrayList<>(mListeners)) {
            l.onPlayerChanged();
        }
    }
}
