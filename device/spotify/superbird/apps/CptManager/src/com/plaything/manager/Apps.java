/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.plaything.manager;

import android.app.ActivityManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Debug;
import android.os.Process;
import android.os.storage.StorageManager;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The installed apps, their storage (StorageStatsManager), and the memory of their running
 * processes (PSS). The measurements are slow (a binder call to installd for each package), so
 * the activity calls them on a worker thread; apply() then copies the results into the list on
 * the main thread.
 */
final class Apps {
    private static final String TAG = "CptManager";
    // Not in the list: the home screen and this app (Stop or Clear data would break them).
    private static final Set<String> HIDDEN = new HashSet<>();

    static {
        HIDDEN.add("com.plaything.home");
        HIDDEN.add("com.plaything.manager");
    }

    static final class App {
        String pkg;
        String label;
        String versionName;
        // In the image (/system), and with an update in /data.
        boolean system;
        boolean updated;
        // The label of the app that installed it, or null.
        String installer;
        // Bytes; -1 until measured. data includes cache (StorageStats).
        long appBytes = -1;
        long dataBytes = -1;
        long cacheBytes = -1;
        // The PSS of its running processes, in KiB; 0 = not running.
        long pssKb;

        long totalBytes() {
            return appBytes < 0 ? -1 : appBytes + dataBytes;
        }
    }

    /**
     * The apps of the list, sorted by name: each app from outside the image, each updated app
     * of the image, and each app of the image with a launcher entry.
     */
    static List<App> load(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> launchable = new HashSet<>();
        for (String category : new String[] {Intent.CATEGORY_LAUNCHER,
                Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                launchable.add(ri.activityInfo.packageName);
            }
        }
        List<App> apps = new ArrayList<>();
        for (PackageInfo p : pm.getInstalledPackages(0)) {
            ApplicationInfo ai = p.applicationInfo;
            if (ai == null || HIDDEN.contains(p.packageName)) {
                continue;
            }
            App a = new App();
            a.pkg = p.packageName;
            a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            a.updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (a.system && !a.updated && !launchable.contains(a.pkg)) {
                continue;
            }
            a.label = ai.loadLabel(pm).toString();
            a.versionName = p.versionName;
            String installer = null;
            try {
                installer = pm.getInstallerPackageName(a.pkg);
            } catch (IllegalArgumentException e) {
                // Not installed any more.
            }
            if (installer != null) {
                try {
                    a.installer = pm.getApplicationLabel(pm.getApplicationInfo(installer, 0))
                            .toString();
                } catch (PackageManager.NameNotFoundException e) {
                    a.installer = installer;
                }
            }
            apps.add(a);
        }
        apps.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
        return apps;
    }

    /** The storage of a package, in bytes. data includes cache (StorageStats). */
    static final class Sizes {
        long app;
        long data;
        long cache;
    }

    /** The storage of each installed package (also the packages that the list does not show). */
    static Map<String, Sizes> measureSizes(Context context) {
        StorageStatsManager ssm = context.getSystemService(StorageStatsManager.class);
        Map<String, Sizes> out = new HashMap<>();
        for (PackageInfo p : context.getPackageManager().getInstalledPackages(0)) {
            StorageStats s;
            try {
                s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, p.packageName,
                        Process.myUserHandle());
            } catch (PackageManager.NameNotFoundException | IOException | RuntimeException e) {
                Log.w(TAG, "size of " + p.packageName + ": " + e);
                continue;
            }
            Sizes z = new Sizes();
            z.app = s.getAppBytes();
            z.data = s.getDataBytes();
            z.cache = s.getCacheBytes();
            out.put(p.packageName, z);
        }
        return out;
    }

    /** The PSS of the running processes of each package, in KiB (shared ones count for each). */
    static Map<String, Long> measureMemory(Context context) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        Map<String, Long> out = new HashMap<>();
        if (procs == null || procs.isEmpty()) {
            return out;
        }
        int[] pids = new int[procs.size()];
        for (int i = 0; i < pids.length; i++) {
            pids[i] = procs.get(i).pid;
        }
        Debug.MemoryInfo[] info = am.getProcessMemoryInfo(pids);
        for (int i = 0; i < pids.length && i < info.length; i++) {
            String[] pkgs = procs.get(i).pkgList;
            for (int j = 0; pkgs != null && j < pkgs.length; j++) {
                Long old = out.get(pkgs[j]);
                out.put(pkgs[j], (old != null ? old : 0) + info[i].getTotalPss());
            }
        }
        return out;
    }

    /** Applies the measurements to the apps of the list (on the main thread). */
    static void apply(List<App> apps, Map<String, Sizes> sizes, Map<String, Long> memory) {
        for (App a : apps) {
            Sizes z = sizes != null ? sizes.get(a.pkg) : null;
            if (z != null) {
                a.appBytes = z.app;
                a.dataBytes = z.data;
                a.cacheBytes = z.cache;
            }
            if (memory != null) {
                Long pss = memory.get(a.pkg);
                a.pssKb = pss != null ? pss : 0;
            }
        }
    }
}
