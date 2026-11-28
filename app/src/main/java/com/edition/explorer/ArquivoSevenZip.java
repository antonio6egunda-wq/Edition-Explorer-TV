package com.edition.explorer;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/** Extração de 7Z. Carregada só quando usada, para não afetar o resto da app. */
final class ArquivoSevenZip {

    private ArquivoSevenZip() {
    }

    static void extrair(File arquivo, File dest, String raiz) throws IOException {
        try (SevenZFile sz = new SevenZFile(arquivo)) {
            SevenZArchiveEntry e;
            byte[] buf = new byte[65536];
            while ((e = sz.getNextEntry()) != null) {
                File out = FileOps.seguro(dest, e.getName(), raiz);
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File pai = out.getParentFile();
                if (pai != null) pai.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = sz.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("password") || m.contains("encrypt")) {
                throw new IOException("Arquivo protegido por senha");
            }
            throw e;
        }
    }
}
