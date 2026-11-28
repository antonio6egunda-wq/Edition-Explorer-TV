package com.edition.explorer;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
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
     * Bloqueia entradas que tentem sair da pasta de destino (ex.: ../../arquivo).
     */
    public static File descompactar(File arquivo, File destinoDir) throws IOException {
        String n = arquivo.getName().toLowerCase(Locale.ROOT);
        String nome = arquivo.getName();
        int dot = nome.lastIndexOf('.');
        if (dot > 0) nome = nome.substring(0, dot);

        File dest = uniqueName(destinoDir, nome);
        if (!dest.mkdirs() && !dest.isDirectory()) throw new IOException("Não foi possível criar a pasta");
        String raiz = dest.getCanonicalPath() + File.separator;

        if (n.endsWith(".zip")) {
            extrairZip(arquivo, dest, raiz);
        } else if (n.endsWith(".7z")) {
            extrair7z(arquivo, dest, raiz);
        } else if (n.endsWith(".rar")) {
            extrairRar(arquivo, dest, raiz);
        } else {
            throw new IOException("Formato não suportado");
        }
        return dest;
    }

    /** Nomes de ZIP criados no Windows costumam estar em CP850; tenta UTF-8 e depois CP850. */
    private static void extrairZip(File arquivo, File dest, String raiz) throws IOException {
        String codificacao = "UTF-8";
        try (ZipFile teste = new ZipFile(arquivo, "UTF-8")) {
            Enumeration<ZipArchiveEntry> en = teste.getEntries();
            while (en.hasMoreElements()) {
                if (en.nextElement().getName().indexOf('\uFFFD') >= 0) {
                    codificacao = "CP850";
                    break;
                }
            }
        }

        try (ZipFile zf = new ZipFile(arquivo, codificacao)) {
            Enumeration<ZipArchiveEntry> en = zf.getEntries();
            while (en.hasMoreElements()) {
                ZipArchiveEntry e = en.nextElement();
                if (e.getGeneralPurposeBit().usesEncryption()) {
                    throw new IOException("Arquivo protegido por senha");
                }
                File out = seguro(dest, e.getName(), raiz);
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File pai = out.getParentFile();
                    if (pai != null) pai.mkdirs();
                    try (InputStream in = zf.getInputStream(e);
                         OutputStream os = new FileOutputStream(out)) {
                        copiar(in, os);
                    }
                }
            }
        }
    }

    private static void extrair7z(File arquivo, File dest, String raiz) throws IOException {
        try (SevenZFile sz = new SevenZFile(arquivo)) {
            SevenZArchiveEntry e;
            while ((e = sz.getNextEntry()) != null) {
                File out = seguro(dest, e.getName(), raiz);
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File pai = out.getParentFile();
                    if (pai != null) pai.mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = sz.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
            }
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("password")) {
                throw new IOException("Arquivo protegido por senha");
            }
            throw e;
        }
    }

    private static void extrairRar(File arquivo, File dest, String raiz) throws IOException {
        try (Archive rar = new Archive(arquivo)) {
            if (rar.isEncrypted()) {
                throw new IOException("Arquivo protegido por senha");
            }
            FileHeader fh;
            while ((fh = rar.nextFileHeader()) != null) {
                File out = seguro(dest, fh.getFileName().replace('\\', '/'), raiz);
                if (fh.isDirectory()) {
                    out.mkdirs();
                } else {
                    File pai = out.getParentFile();
                    if (pai != null) pai.mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        rar.extractFile(fh, os);
                    }
                }
            }
        } catch (com.github.junrar.exception.RarException e) {
            throw new IOException("Arquivo RAR inválido ou não suportado");
        }
    }

    /** Resolve o caminho de uma entrada e recusa qualquer um que saia da pasta de destino. */
    private static File seguro(File dest, String entrada, String raiz) throws IOException {
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
