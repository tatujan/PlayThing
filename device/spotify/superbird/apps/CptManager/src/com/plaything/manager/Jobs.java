/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.manager;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The actions of the App manager, one at a time, with the APIs that Settings uses (the app has
 * the platform key): ActivityManager.forceStopPackage, PackageManager.deleteApplicationCacheFiles,
 * ActivityManager.clearApplicationUserData, and PackageInstaller.uninstall. Each reports to the
 * main thread.
 */
final class Jobs {
    interface Done {
        void done(boolean ok, String error);
    }

    interface Progress {
        void progress(int done, int total);
    }

    private static final String TAG = "CptManager";
    private static final String ACTION_REMOVED = "com.plaything.manager.REMOVED";
    private static final String EXTRA_JOB = "com.plaything.manager.JOB";
    private static final long CACHE_TIMEOUT_S = 30;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private boolean mBusy;
    private int mJobId;
    private Done mRemoveDone;

    // The result of PackageInstaller.uninstall().
    private final BroadcastReceiver mRemoved = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mRemoveDone == null || intent.getIntExtra(EXTRA_JOB, 0) != mJobId) {
                return;
            }
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    mContext.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                return;
            }
            Done done = mRemoveDone;
            mRemoveDone = null;
            finish(done, status == PackageInstaller.STATUS_SUCCESS,
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    };

    Jobs(Context context) {
        mContext = context.getApplicationContext();
        mContext.registerReceiver(mRemoved, new IntentFilter(ACTION_REMOVED));
    }

    void close() {
        mContext.unregisterReceiver(mRemoved);
        mWorker.shutdown();
    }

    boolean busy() {
        return mBusy;
    }

    boolean stop(String pkg, Done done) {
        return start(done, () -> {
            mContext.getSystemService(ActivityManager.class).forceStopPackage(pkg);
            finish(done, true, null);
        });
    }

    boolean clearCache(String pkg, Done done) {
        return start(done, () -> {
            boolean ok = clearCacheNow(pkg);
            finish(done, ok, ok ? null : "timeout");
        });
    }

    boolean clearData(String pkg, Done done) {
        return start(done, () -> {
            CountDownLatch latch = new CountDownLatch(1);
            boolean[] result = {false};
            boolean started = mContext.getSystemService(ActivityManager.class)
                    .clearApplicationUserData(pkg, new IPackageDataObserver.Stub() {
                        @Override
                        public void onRemoveCompleted(String packageName, boolean succeeded) {
                            result[0] = succeeded;
                            latch.countDown();
                        }
                    });
            if (!started) {
                finish(done, false, "refused");
                return;
            }
            finish(done, await(latch) && result[0], null);
        });
    }

    boolean remove(String pkg, Done done) {
        if (mBusy) {
            return false;
        }
        mBusy = true;
        mJobId++;
        mRemoveDone = done;
        Intent intent = new Intent(ACTION_REMOVED)
                .setPackage(mContext.getPackageName())
                .putExtra(EXTRA_JOB, mJobId);
        try {
            mContext.getPackageManager().getPackageInstaller().uninstall(pkg,
                    PendingIntent.getBroadcast(mContext, mJobId, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
        } catch (RuntimeException e) {
            mRemoveDone = null;
            finish(done, false, e.getMessage());
        }
        return true;
    }

    /** Clears the cache of each package in the list; reports the progress. */
    boolean cleanup(List<String> pkgs, Progress progress, Done done) {
        return start(done, () -> {
            int ok = 0;
            for (int i = 0; i < pkgs.size(); i++) {
                int n = i;
                mMain.post(() -> progress.progress(n, pkgs.size()));
                if (clearCacheNow(pkgs.get(i))) {
                    ok++;
                }
            }
            finish(done, ok == pkgs.size(), ok == pkgs.size() ? null
                    : (pkgs.size() - ok) + " of " + pkgs.size());
        });
    }

    /** Clears one cache on the worker thread, and waits for the result. */
    private boolean clearCacheNow(String pkg) {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = {false};
        mContext.getPackageManager().deleteApplicationCacheFiles(pkg,
                new IPackageDataObserver.Stub() {
                    @Override
                    public void onRemoveCompleted(String packageName, boolean succeeded) {
                        result[0] = succeeded;
                        latch.countDown();
                    }
                });
        return await(latch) && result[0];
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(CACHE_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean start(Done done, Runnable job) {
        if (mBusy) {
            return false;
        }
        mBusy = true;
        mWorker.execute(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "job failed", e);
                finish(done, false, e.getMessage());
            }
        });
        return true;
    }

    private void finish(Done done, boolean ok, String error) {
        mMain.post(() -> {
            mBusy = false;
            if (done != null) {
                done.done(ok, error);
            }
        });
    }
}
