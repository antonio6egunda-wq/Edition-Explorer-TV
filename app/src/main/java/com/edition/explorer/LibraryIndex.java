package com.edition.explorer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Indexa, em segundo plano, os ficheiros de vídeo, música, fotos e documentos de todos os volumes. */
public final class LibraryIndex {

    public enum Category { VIDEO, AUDIO, IMAGE, DOCUMENT }

    private static final Set<String> VIDEO_EXT = set("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "3gp");
    private static final Set<String> AUDIO_EXT = set("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus");
    private static final Set<String> IMAGE_EXT = set("jpg", "jpeg", "png", "webp", "gif", "bmp");
    private static final Set<String> DOC_EXT = set("apk", "zip", "docx", "pdf", "txt");

    private static final int MAX_DEPTH = 12;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static final Map<Category, List<File>> result = new EnumMap<>(Category.class);
    private static boolean ready = false;

    private LibraryIndex() {
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    public static synchronized boolean isReady() {
        return ready;
    }

    public static synchronized void invalidate() {
        ready = false;
    }

    public static synchronized List<File> items(Category c) {
        List<File> l = result.get(c);
        return l == null ? Collections.<File>emptyList() : l;
    }

    public static synchronized int count(Category c) {
        return items(c).size();
    }

    /** Faz uma nova leitura em segundo plano e avisa na thread principal quando acabar. */
    public static void refresh(Context ctx, Runnable onDone) {
        final Context app = ctx.getApplicationContext();
        executor.execute(() -> {
            Map<Category, List<File>> map = new EnumMap<>(Category.class);
            for (Category c : Category.values()) map.put(c, new ArrayList<File>());

            for (StorageHelper.Volume v : StorageHelper.getVolumes(app)) {
                walk(v.root, 0, map);
            }
            for (List<File> l : map.values()) {
                Collections.sort(l, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            }

            synchronized (LibraryIndex.class) {
                result.clear();
                result.putAll(map);
                ready = true;
            }
            if (onDone != null) main.post(onDone);
        });
    }

    private static void walk(File dir, int depth, Map<Category, List<File>> map) {
        if (depth > MAX_DEPTH) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                // Android/data e Android/obb são dados de outras apps: não interessam aqui.
                if (depth == 0 && name.equals("Android")) continue;
                walk(f, depth + 1, map);
            } else {
                Category c = categoryOf(name);
                if (c != null) map.get(c).add(f);
            }
        }
    }

    public static Category categoryOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (VIDEO_EXT.contains(ext)) return Category.VIDEO;
        if (AUDIO_EXT.contains(ext)) return Category.AUDIO;
        if (IMAGE_EXT.contains(ext)) return Category.IMAGE;
        if (DOC_EXT.contains(ext)) return Category.DOCUMENT;
        return null;
    }
}
