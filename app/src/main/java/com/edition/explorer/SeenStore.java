package com.edition.explorer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Controla o ponto azul de "novo". Só se aplica à pasta Download.
 * Na primeira execução, os ficheiros que já existem são marcados como vistos.
 */
public final class SeenStore {

    private static final String PREFS = "seen";
    private static final String KEY_SEEN = "seen_paths";
    private static final String KEY_INIT = "initialized";

    private SeenStore() {
    }

    public static File downloadsDir() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void initIfNeeded(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getBoolean(KEY_INIT, false)) return;
        Set<String> all = new HashSet<>();
        File[] files = downloadsDir().listFiles();
        if (files != null) {
            for (File f : files) all.add(f.getAbsolutePath());
        }
        p.edit().putStringSet(KEY_SEEN, all).putBoolean(KEY_INIT, true).apply();
    }

    public static boolean isInDownloads(File f) {
        String dl = downloadsDir().getAbsolutePath();
        return f.getAbsolutePath().startsWith(dl + "/");
    }

    public static boolean isNew(Context ctx, File f) {
        if (!isInDownloads(f) || !f.isFile()) return false;
        return !seen(ctx).contains(f.getAbsolutePath());
    }

    public static void markSeen(Context ctx, File f) {
        if (!isInDownloads(f)) return;
        Set<String> s = new HashSet<>(seen(ctx));
        if (s.add(f.getAbsolutePath())) {
            prefs(ctx).edit().putStringSet(KEY_SEEN, s).apply();
        }
    }

    public static int unseenCount(Context ctx) {
        File[] files = downloadsDir().listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            if (isNew(ctx, f)) n++;
        }
        return n;
    }

    private static Set<String> seen(Context ctx) {
        Set<String> s = prefs(ctx).getStringSet(KEY_SEEN, null);
        return s == null ? Collections.<String>emptySet() : s;
    }
}
