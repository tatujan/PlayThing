/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.pod;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.LruCache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The music library: the songs of Android's media library (MediaStore), on the device and on USB
 * sticks (Android's media scanner reads them), grouped by artist and album. The cover art comes
 * from the media library, decoded small (RGB_565) and kept in a small cache.
 */
final class Library {
    static final class Song {
        long id;
        String title;
        String artist;
        String album;
        long albumId;
        int track;
        long durationMs;

        Uri uri() {
            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        }
    }

    static final class Album {
        String name;
        String artist;
        long id;
        final List<Song> songs = new ArrayList<>();
    }

    static final class Artist {
        String name;
        final List<Album> albums = new ArrayList<>();

        List<Song> songs() {
            List<Song> out = new ArrayList<>();
            for (Album a : albums) {
                out.addAll(a.songs);
            }
            return out;
        }
    }

    private static final Uri ALBUM_ART = Uri.parse("content://media/external/audio/albumart");
    // About 4 covers of 300 x 300 in RGB_565 (180 KB each).
    private static final int ART_CACHE_BYTES = 800 * 1024;
    private static final Comparator<String> BY_NAME = (a, b) -> key(a).compareTo(key(b));

    final List<Song> songs = new ArrayList<>();
    final List<Artist> artists = new ArrayList<>();
    final List<Album> albums = new ArrayList<>();
    private final Map<Long, Song> mById = new LinkedHashMap<>();
    private final LruCache<String, Bitmap> mArt = new LruCache<String, Bitmap>(ART_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** Reads the library (slow: call it on a worker thread). */
    static Library load(Context context) {
        Library lib = new Library();
        String[] cols = {
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
        };
        ContentResolver cr = context.getContentResolver();
        try (Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)) {
            while (c != null && c.moveToNext()) {
                Song s = new Song();
                s.id = c.getLong(0);
                s.title = text(c.getString(1), "Unknown");
                s.artist = text(c.getString(2), "Unknown artist");
                s.album = text(c.getString(3), "Unknown album");
                s.albumId = c.getLong(4);
                // MediaStore stores the disc number in the thousands.
                s.track = c.getInt(5) % 1000;
                s.durationMs = c.getLong(6);
                lib.songs.add(s);
                lib.mById.put(s.id, s);
            }
        } catch (SecurityException e) {
            // No storage permission: an empty library.
        }
        Collections.sort(lib.songs, (a, b) -> BY_NAME.compare(a.title, b.title));

        Map<String, Artist> artists = new LinkedHashMap<>();
        Map<Long, Album> albums = new LinkedHashMap<>();
        for (Song s : lib.songs) {
            Album al = albums.get(s.albumId);
            if (al == null) {
                al = new Album();
                al.name = s.album;
                al.artist = s.artist;
                al.id = s.albumId;
                albums.put(s.albumId, al);
                String key = key(s.artist);
                Artist ar = artists.get(key);
                if (ar == null) {
                    ar = new Artist();
                    ar.name = s.artist;
                    artists.put(key, ar);
                }
                ar.albums.add(al);
            }
            al.songs.add(s);
        }
        for (Album al : albums.values()) {
            Collections.sort(al.songs, (a, b) -> a.track != b.track ? a.track - b.track
                    : BY_NAME.compare(a.title, b.title));
            lib.albums.add(al);
        }
        Collections.sort(lib.albums, (a, b) -> BY_NAME.compare(a.name, b.name));
        lib.artists.addAll(artists.values());
        Collections.sort(lib.artists, (a, b) -> BY_NAME.compare(a.name, b.name));
        for (Artist ar : lib.artists) {
            Collections.sort(ar.albums, (a, b) -> BY_NAME.compare(a.name, b.name));
        }
        return lib;
    }

    Song song(long id) {
        return mById.get(id);
    }

    /** The cover of an album, at most size x size pixels, or null. Call it on a worker thread. */
    Bitmap art(Context context, long albumId, int size) {
        String key = albumId + "@" + size;
        Bitmap b = mArt.get(key);
        if (b != null) {
            return b;
        }
        Uri uri = ContentUris.withAppendedId(ALBUM_ART, albumId);
        try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd == null) {
                return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            b = BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, o);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (b != null) {
            mArt.put(key, b);
        }
        return b;
    }

    /** Sort key: no case, and no leading "The " (as on the iPod). */
    private static String key(String s) {
        String k = s.toLowerCase(Locale.ROOT);
        return k.startsWith("the ") ? k.substring(4) : k;
    }

    private static String text(String s, String unknown) {
        return s == null || s.isEmpty() || "<unknown>".equals(s) ? unknown : s;
    }
}
