package com.edition.explorer;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Extração de RAR. Carregada só quando usada, para não afetar o resto da app. */
final class ArquivoRar {

    private ArquivoRar() {
    }

    static void extrair(File arquivo, File dest, String raiz) throws IOException {
        try (Archive rar = new Archive(arquivo)) {
            if (rar.isEncrypted()) {
                throw new IOException("Arquivo protegido por senha");
            }
            FileHeader fh;
            while ((fh = rar.nextFileHeader()) != null) {
                File out = FileOps.seguro(dest, fh.getFileName().replace('\\', '/'), raiz);
                if (fh.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File pai = out.getParentFile();
                if (pai != null) pai.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    rar.extractFile(fh, os);
                }
            }
        } catch (RarException e) {
            throw new IOException("Arquivo RAR inválido ou não suportado");
        }
    }
}
