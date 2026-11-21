package com.edition.explorer;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Descobre a memória interna, os dispositivos USB (OTG, hubs e portas) e os cartões SD.
 * Procura em /storage e em /mnt/media_rw, porque o sistema usa caminhos diferentes em cada TV Box/tablet.
 */
public final class StorageHelper {

    public static final int TYPE_INTERNAL = 0;
    public static final int TYPE_USB = 1;
    public static final int TYPE_SD = 2;

    private static final String[] BASES = {"/storage", "/mnt/media_rw"};

    public static final class Volume {
        public final int type;
        public final String label;
        public final File root;

        Volume(int type, String label, File root) {
            this.type = type;
            this.label = label;
            this.root = root;
        }

        public long total() {
            try {
                return new StatFs(root.getAbsolutePath()).getTotalBytes();
            } catch (IllegalArgumentException e) {
                return 0;
            }
        }

        public long free() {
            try {
                return new StatFs(root.getAbsolutePath()).getAvailableBytes();
            } catch (IllegalArgumentException e) {
                return 0;
            }
        }
    }

    private StorageHelper() {
    }

    /** Memória interna primeiro, depois todos os USB e todos os cartões SD encontrados. */
    public static List<Volume> getVolumes(Context ctx) {
        List<Volume> out = new ArrayList<>();
        File internal = Environment.getExternalStorageDirectory();
        out.add(new Volume(TYPE_INTERNAL, ctx.getString(R.string.card_armazenamento), internal));

        // Nomes de ponto de montagem (ex.: 1234-ABCD, sda1) vindos das duas origens
        Set<String> nomes = new LinkedHashSet<>();
        for (String base : BASES) {
            File[] fs = new File(base).listFiles();
            if (fs == null) continue;
            for (File f : fs) nomes.add(f.getName());
        }

        List<File> usb = new ArrayList<>();
        List<File> sd = new ArrayList<>();
        for (String nome : nomes) {
            if (nome.equals("emulated") || nome.equals("self") || nome.equals("sdcard0")) continue;

            File root = raizDe(nome);
            if (root == null || estaNaMemoriaInterna(root, internal)) continue;

            if (tipoPorMontagem(nome) == TYPE_SD) sd.add(root);
            else usb.add(root); // desconhecido ou sd*: tratado como USB (OTG é o caso mais comum)
        }

        for (int i = 0; i < usb.size(); i++) {
            out.add(new Volume(TYPE_USB, "Drive " + (i + 1), usb.get(i)));
        }
        for (int i = 0; i < sd.size(); i++) {
            out.add(new Volume(TYPE_SD, "Cartão SD " + (i + 1), sd.get(i)));
        }
        return out;
    }

    /** Todos os volumes de um tipo (pode haver várias portas USB). */
    public static List<Volume> findAll(List<Volume> volumes, int type) {
        List<Volume> r = new ArrayList<>();
        for (Volume v : volumes) {
            if (v.type == type) r.add(v);
        }
        return r;
    }

    /** Caminho utilizável do volume: /storage/NOME se existir, senão /mnt/media_rw/NOME. */
    private static File raizDe(String nome) {
        for (String base : BASES) {
            File f = new File(base, nome);
            if (f.isDirectory() && f.canRead()) return f;
        }
        return null;
    }

    private static boolean estaNaMemoriaInterna(File root, File internal) {
        try {
            String canonical = root.getCanonicalPath();
            return canonical.startsWith("/storage/emulated") || canonical.equals(internal.getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Lê /proc/mounts: cartões SD usam mmcblk (major 179), USB usam sd* (major 8).
     * Devolve TYPE_SD, TYPE_USB, ou -1 se não der para saber.
     */
    private static int tipoPorMontagem(String nome) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] campos = line.split(" ");
                if (campos.length < 2) continue;
                String origem = campos[0].toLowerCase(Locale.ROOT);
                String destino = campos[1];
                if (!destino.endsWith("/" + nome)) continue;

                if (origem.contains("mmcblk") || origem.contains("public:179,")) return TYPE_SD;
                if (origem.contains("/sd") || origem.contains("public:8,")) return TYPE_USB;
            }
        } catch (IOException ignored) {
        }
        return -1;
    }
}
