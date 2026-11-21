package com.edition.explorer;

import android.content.Context;
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
import java.util.List;
import java.util.Locale;

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
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

    public FileAdapter(Context ctx, int mode, boolean selectMode, Callback cb) {
        this.ctx = ctx;
        this.mode = mode;
        this.selectMode = selectMode;
        this.cb = cb;
    }

    public void setItems(List<File> files) {
        items.clear();
        items.addAll(files);
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

        h.tvName.setText(f.getName());
        h.viewDot.setVisibility(cb.isNew(f) ? View.VISIBLE : View.GONE);

        boolean showCheck = selectMode && !f.isDirectory();
        h.viewCheck.setVisibility(showCheck ? View.VISIBLE : View.GONE);
        h.viewCheck.setSelected(showCheck && cb.isSelected(f));

        if (h.tvDetails != null) h.tvDetails.setText(detalhes(f));

        LibraryIndex.Category cat = f.isDirectory() ? null : LibraryIndex.categoryOf(f.getName());
        boolean media = cat == LibraryIndex.Category.IMAGE || cat == LibraryIndex.Category.VIDEO;

        if (h.ivThumb != null) {
            if (media) {
                h.ivThumb.setVisibility(View.VISIBLE);
                h.ivIcon.setVisibility(View.GONE);
                ThumbnailLoader.load(f, cat == LibraryIndex.Category.VIDEO, h.ivThumb);
            } else {
                h.ivThumb.setTag(null);
                h.ivThumb.setVisibility(View.GONE);
                h.ivIcon.setVisibility(View.VISIBLE);
                h.ivIcon.setImageResource(iconFor(f));
            }
        } else {
            h.ivIcon.setImageResource(iconFor(f));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String detalhes(File f) {
        String size = f.isDirectory() ? ctx.getString(R.string.tipo_pasta) : FileOps.formatSize(f.length());
        return size + "   •   " + dateFormat.format(new Date(f.lastModified()));
    }

    private int iconFor(File f) {
        if (f.isDirectory()) return R.drawable.ic_folder;
        String n = f.getName().toLowerCase(Locale.ROOT);
        LibraryIndex.Category c = LibraryIndex.categoryOf(n);
        if (c == LibraryIndex.Category.VIDEO) return R.drawable.ic_video;
        if (c == LibraryIndex.Category.AUDIO) return R.drawable.ic_audio;
        if (c == LibraryIndex.Category.IMAGE) return R.drawable.ic_image;
        if (n.endsWith(".docx")) return R.drawable.ic_worddocument;
        if (n.endsWith(".txt")) return R.drawable.ic_text;
        return R.drawable.ic_generic;
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final ImageView ivThumb;
        final TextView tvName;
        final TextView tvDetails;
        final View viewDot;
        final View viewCheck;

        VH(View v) {
            super(v);
            ivIcon = v.findViewById(R.id.ivIcon);
            ivThumb = v.findViewById(R.id.ivThumb);
            tvName = v.findViewById(R.id.tvName);
            tvDetails = v.findViewById(R.id.tvDetails);
            viewDot = v.findViewById(R.id.viewDot);
            viewCheck = v.findViewById(R.id.viewCheck);
        }
    }
}
