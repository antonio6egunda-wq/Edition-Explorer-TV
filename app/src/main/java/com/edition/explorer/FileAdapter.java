package com.edition.explorer;

import android.content.Context;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Adaptador da lista de ficheiros nos três modos: detalhado, compacto e grade. */
public class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {

    public interface Callback {
        void onOpen(File f);

        void onLongPress(File f);

        boolean isNew(File f);

        boolean isSelected(File f);
    }

    private final Context ctx;
    private final int mode;
    private final boolean selectMode;
    private final Callback cb;
    private final List<File> items = new ArrayList<>();
    private final Map<String, Integer> contagens = new HashMap<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

    public FileAdapter(Context ctx, int mode, boolean selectMode, Callback cb) {
        this.ctx = ctx;
        this.mode = mode;
        this.selectMode = selectMode;
        this.cb = cb;
    }

    /** Entrada ".." que volta à pasta anterior. */
    public static boolean isParent(File f) {
        return "..".equals(f.getName());
    }

    /** Raiz da memória interna, ou raiz de um USB/SD (/storage/XXXX ou /mnt/media_rw/XXXX). */
    public static boolean estaNaRaizDeArmazenamento(File dir) {
        if (dir == null) return false;
        String p = dir.getAbsolutePath();
        return p.equals(Environment.getExternalStorageDirectory().getAbsolutePath())
                || p.matches("/storage/[^/]+")
                || p.matches("/mnt/media_rw/[^/]+");
    }

    public void setItems(List<File> files) {
        items.clear();
        items.addAll(files);
        contagens.clear();
        notifyDataSetChanged();
    }

    public File getItem(int position) {
        return items.get(position);
    }

    @Override
    public int getItemViewType(int position) {
        return mode;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout;
        if (viewType == BrowserActivity.MODE_GRID) layout = R.layout.item_file_grid;
        else if (viewType == BrowserActivity.MODE_COMPACT) layout = R.layout.item_file_compact;
        else layout = R.layout.item_file_detailed;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final File f = items.get(position);
        h.itemView.setOnClickListener(v -> cb.onOpen(f));
        h.itemView.setOnLongClickListener(v -> {
            cb.onLongPress(f);
            return true;
        });

        boolean pai = isParent(f);
        h.tvName.setText(f.getName());
        h.viewDot.setVisibility(!pai && cb.isNew(f) ? View.VISIBLE : View.GONE);

        boolean showCheck = selectMode && !f.isDirectory();
        h.viewCheck.setVisibility(showCheck ? View.VISIBLE : View.GONE);
        h.viewCheck.setSelected(showCheck && cb.isSelected(f));

        String tamanho = pai ? "" : (f.isDirectory() ? contarItens(f) : FileOps.formatSize(f.length()));
        String data = pai ? "" : dateFormat.format(new Date(f.lastModified()));
        String tipo = pai ? ctx.getString(R.string.pasta_anterior)
                : (f.isDirectory() ? ctx.getString(R.string.tipo_pasta) : tipoDe(f));

        if (h.tvDetails != null) {
            h.tvDetails.setText(pai ? tipo : tamanho + "   •   " + data);
        }
        if (h.tvTipo != null) {
            h.tvTipo.setText(tipo);
            h.tvTamanho.setText(tamanho);
            h.tvData.setText(data);
        }

        LibraryIndex.Category cat = f.isDirectory() ? null : LibraryIndex.categoryOf(f.getName());
        boolean media = cat == LibraryIndex.Category.IMAGE || cat == LibraryIndex.Category.VIDEO;

        if (h.ivThumb != null && media) {
            h.ivThumb.setVisibility(View.VISIBLE);
            h.ivIcon.setVisibility(View.GONE);
            ThumbnailLoader.load(f, cat == LibraryIndex.Category.VIDEO, h.ivThumb);
        } else {
            if (h.ivThumb != null) {
                h.ivThumb.setTag(null);
                h.ivThumb.setVisibility(View.GONE);
            }
            h.ivIcon.setVisibility(View.VISIBLE);
            h.ivIcon.setImageResource(iconFor(f));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** Número de itens visíveis dentro da pasta, calculado uma vez por lista. */
    private int contarFilhos(File f) {
        String chave = f.getAbsolutePath();
        Integer cached = contagens.get(chave);
        if (cached != null) return cached;
        File[] filhos = f.listFiles();
        int n = 0;
        if (filhos != null) {
            for (File x : filhos) {
                if (!x.getName().startsWith(".")) n++;
            }
        }
        contagens.put(chave, n);
        return n;
    }

    private String contarItens(File f) {
        return ctx.getString(R.string.itens_count, contarFilhos(f));
    }

    private String tipoDe(File f) {
        int dot = f.getName().lastIndexOf('.');
        if (dot < 0 || dot == f.getName().length() - 1) return ctx.getString(R.string.tipo_ficheiro);
        return f.getName().substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    private int iconFor(File f) {
        if (f.isDirectory()) return iconeDePasta(f);
        String n = f.getName().toLowerCase(Locale.ROOT);
        LibraryIndex.Category c = LibraryIndex.categoryOf(n);
        if (c == LibraryIndex.Category.VIDEO) return R.drawable.ic_video;
        if (c == LibraryIndex.Category.AUDIO) return R.drawable.ic_audio;
        if (c == LibraryIndex.Category.IMAGE) return R.drawable.ic_image;
        if (n.endsWith(".docx")) return R.drawable.ic_worddocument;
        if (n.endsWith(".txt")) return R.drawable.ic_text;
        if (n.endsWith(".zip")) return R.drawable.ic_zip;
        if (n.endsWith(".rar")) return R.drawable.ic_rar;
        if (n.endsWith(".7z")) return R.drawable.ic_7z;
        if (n.endsWith(".pdf")) return R.drawable.ic_pdf;
        if (n.endsWith(".iso") || n.endsWith(".img") || n.endsWith(".bin")
                || n.endsWith(".cue") || n.endsWith(".mdf") || n.endsWith(".nrg")) return R.drawable.ic_cd_image;
        if (n.endsWith(".apk")) return R.drawable.ic_generic;
        return R.drawable.ic_unknown;
    }

    /**
     * Ícone da pasta:
     * - pastas principais da raiz (Download, Music, Pictures...) usam o ícone próprio;
     * - pasta com ficheiros dentro: ic_folder_filled;
     * - pasta vazia: ic_folder;
     * - ".." (pasta anterior): ic_folder_filled.
     */
    private int iconeDePasta(File f) {
        if (isParent(f)) return R.drawable.ic_folder_filled;

        if (estaNaRaizDeArmazenamento(f.getParentFile())) {
            Integer principal = iconePrincipal(f.getName());
            if (principal != null) return principal;
        }
        return contarFilhos(f) > 0 ? R.drawable.ic_folder_filled : R.drawable.ic_folder;
    }

    private static Integer iconePrincipal(String nome) {
        switch (nome.toLowerCase(Locale.ROOT)) {
            case "download":
            case "downloads":
            case "transferências":
                return R.drawable.ic_folder_downloads;
            case "music":
            case "musica":
            case "música":
            case "músicas":
                return R.drawable.ic_folder_music;
            case "pictures":
            case "picture":
            case "imagens":
            case "fotos":
            case "images":
            case "dcim":
                return R.drawable.ic_folder_pictures;
            case "movies":
            case "videos":
            case "vídeos":
            case "filmes":
            case "video":
                return R.drawable.ic_folder_videos;
            case "documents":
            case "documentos":
            case "document":
                return R.drawable.ic_folder_documents;
            default:
                return null;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final ImageView ivThumb;
        final TextView tvName;
        final TextView tvDetails;
        final TextView tvTipo;
        final TextView tvTamanho;
        final TextView tvData;
        final View viewDot;
        final View viewCheck;

        VH(View v) {
            super(v);
            ivIcon = v.findViewById(R.id.ivIcon);
            ivThumb = v.findViewById(R.id.ivThumb);
            tvName = v.findViewById(R.id.tvName);
            tvDetails = v.findViewById(R.id.tvDetails);
            tvTipo = v.findViewById(R.id.tvTipo);
            tvTamanho = v.findViewById(R.id.tvTamanho);
            tvData = v.findViewById(R.id.tvData);
            viewDot = v.findViewById(R.id.viewDot);
            viewCheck = v.findViewById(R.id.viewCheck);
        }
    }
}
