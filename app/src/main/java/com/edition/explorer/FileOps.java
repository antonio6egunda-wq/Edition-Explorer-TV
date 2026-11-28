package com.edition.explorer;

import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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

    // ---------- Arquivos compactados ----------

    /** Formatos que a app consegue descompactar. */
    public static boolean ehArquivo(File f) {
        if (!f.isFile()) return false;
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".7z") || n.endsWith(".rar");
    }

    /** Compacta ficheiros e pastas num ZIP dentro de destinoDir. */
    public static File zipar(List<File> origens, File destinoDir, String nomeZip) throws IOException {
        File zip = uniqueName(destinoDir, nomeZip);
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            for (File f : origens) {
                adicionarAoZip(zos, f, f.getName());
            }
        }
        return zip;
    }

    private static void adicionarAoZip(ZipOutputStream zos, File f, String entrada) throws IOException {
        if (f.isDirectory()) {
            zos.putNextEntry(new ZipEntry(entrada + "/"));
            zos.closeEntry();
            File[] filhos = f.listFiles();
            if (filhos != null) {
                for (File c : filhos) adicionarAoZip(zos, c, entrada + "/" + c.getName());
            }
            return;
        }
        zos.putNextEntry(new ZipEntry(entrada));
        try (InputStream in = new FileInputStream(f)) {
            copiar(in, zos);
        }
        zos.closeEntry();
    }

    /**
     * Descompacta ZIP, 7Z ou RAR para uma pasta com o nome do arquivo, dentro de destinoDir.
     * Se falhar, apaga a pasta parcial. Entradas que saiam da pasta de destino são recusadas.
     */
    public static File descompactar(File arquivo, File destinoDir) throws IOException {
        String n = arquivo.getName().toLowerCase(Locale.ROOT);
        String nome = arquivo.getName();
        int dot = nome.lastIndexOf('.');
        if (dot > 0) nome = nome.substring(0, dot);

        if (!(n.endsWith(".zip") || n.endsWith(".7z") || n.endsWith(".rar"))) {
            throw new IOException("Formato não suportado");
        }

        File dest = uniqueName(destinoDir, nome);
        if (!dest.mkdirs() && !dest.isDirectory()) throw new IOException("Não foi possível criar a pasta");
        try {
            String raiz = dest.getCanonicalPath() + File.separator;
            if (n.endsWith(".zip")) {
                extrairZip(arquivo, dest, raiz);
            } else if (n.endsWith(".7z")) {
                ArquivoSevenZip.extrair(arquivo, dest, raiz);
            } else {
                ArquivoRar.extrair(arquivo, dest, raiz);
            }
        } catch (IOException e) {
            deleteRecursive(dest);
            throw e;
        } catch (Throwable t) {
            // Biblioteca indisponível neste aparelho (ex.: versão de Android antiga)
            deleteRecursive(dest);
            throw new IOException("Não foi possível descompactar este formato neste aparelho ("
                    + t.getClass().getSimpleName() + ")");
        }
        return dest;
    }

    /**
     * ZIP com java.util.zip (disponível em todas as versões do Android).
     * Tenta UTF-8; se os nomes não forem UTF-8 (ZIP feito no Windows), tenta CP850.
     */
    private static void extrairZip(File arquivo, File dest, String raiz) throws IOException {
        try {
            extrairZipCom(arquivo, dest, raiz, null);
        } catch (IllegalArgumentException nomesInvalidos) {
            if (Build.VERSION.SDK_INT < 24) {
                throw new IOException("Nomes de ficheiros não suportados neste aparelho");
            }
            extrairZipCom(arquivo, dest, raiz, Charset.forName("CP850"));
        }
    }

    private static void extrairZipCom(File arquivo, File dest, String raiz, Charset cs) throws IOException {
        InputStream base = new BufferedInputStream(new FileInputStream(arquivo));
        ZipInputStream zis = cs == null ? new ZipInputStream(base) : new ZipInputStream(base, cs);
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                File out = seguro(dest, e.getName(), raiz);
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File pai = out.getParentFile();
                    if (pai != null) pai.mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        copiar(zis, os);
                    }
                }
                zis.closeEntry();
            }
        } catch (ZipException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("encrypt")) {
                throw new IOException("Arquivo protegido por senha");
            }
            throw e;
        } finally {
            zis.close();
        }
    }

    /** Resolve o caminho de uma entrada e recusa qualquer um que saia da pasta de destino. */
    static File seguro(File dest, String entrada, String raiz) throws IOException {
        File out = new File(dest, entrada);
        if (!out.getCanonicalPath().startsWith(raiz)) {
            throw new IOException("Entrada inválida no arquivo: " + entrada);
        }
        return out;
    }

    private static void copiar(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
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
                copiar(in, out);
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
