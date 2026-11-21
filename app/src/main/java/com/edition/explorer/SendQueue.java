package com.edition.explorer;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ficheiros marcados na TV para enviar ao telemóvel/PC. Mantém-se ao navegar entre pastas. */
public final class SendQueue {

    private static final Set<File> selected = new LinkedHashSet<>();

    private SendQueue() {
    }

    public static synchronized boolean contains(File f) {
        return selected.contains(f);
    }

    public static synchronized void toggle(File f) {
        if (!selected.remove(f)) selected.add(f);
    }

    public static synchronized int count() {
        return selected.size();
    }

    public static synchronized List<File> files() {
        return new ArrayList<>(selected);
    }

    public static synchronized void clear() {
        selected.clear();
    }
}
