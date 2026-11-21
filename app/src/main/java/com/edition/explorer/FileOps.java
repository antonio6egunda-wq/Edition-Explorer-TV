package com.edition.explorer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;

/** Operações de ficheiros. Devem ser chamadas fora da thread principal. */
public final class FileOps {

    private FileOps() {
    }

    public static boolean rename(File src, String newName) {
        File dst = new File(src.getParentFile(), newName);
        return !dst.exists() && src.renameTo(dst);
    }

    public static boolean delete(File f) {
        return deleteRecursive(f);
    }

    public static boolean copy(File src, File dstDir) throws IOException {
        copyRecursive(src, uniqueName(dstDir, src.getName()));
        return true;
    }

    public static boolean move(File src, File dstDir) throws IOException {
        File dst = uniqueName(dstDir, src.getName());
        if (src.renameTo(dst)) return true;
        copyRecursive(src, dst);
        return deleteRecursive(src);
    }

    /** Evita sobrescrever: "foto.jpg" -> "foto (1).jpg" se já existir. */
    public static File uniqueName(File dir, String name) {
        File dest = new File(dir, name);
        if (!dest.exists()) return dest;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        while (dest.exists()) {
            dest = new File(dir, base + " (" + i++ + ")" + ext);
        }
        return dest;
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        return f.delete();
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.mkdirs() && !dst.isDirectory()) throw new IOException("Não foi possível criar a pasta");
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) copyRecursive(c, new File(dst, c.getName()));
            }
        } else {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int g = Math.min((int) (Math.log10(bytes) / Math.log10(1024)), units.length - 1);
        return new DecimalFormat("#,##0.#").format(bytes / Math.pow(1024, g)) + " " + units[g];
    }
}
