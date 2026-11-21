package com.edition.explorer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Miniaturas de fotos e vídeos, com cache em memória e carregamento fora da thread principal. */
public final class ThumbnailLoader {

    private static final int THUMB_SIZE = 320;

    private static final LruCache<String, Bitmap> cache =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8 / 1024)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private ThumbnailLoader() {
    }

    /** Mostra a miniatura; se a vista for reciclada entretanto, o resultado é descartado. */
    public static void load(File file, boolean video, ImageView view) {
        final String key = file.getAbsolutePath();
        view.setTag(key);

        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageResource(video ? R.drawable.ic_video : R.drawable.ic_image);

        executor.execute(() -> {
            Bitmap b = video ? videoFrame(key) : decode(key);
            if (b == null) return;
            cache.put(key, b);
            main.post(() -> {
                if (key.equals(view.getTag())) view.setImageBitmap(b);
            });
        });
    }

    private static Bitmap decode(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap videoFrame(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            Bitmap frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            return scale(frame);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static int sampleFor(int w, int h) {
        int sample = 1;
        int max = Math.max(w, h);
        while (max / (sample * 2) >= THUMB_SIZE) sample *= 2;
        return sample;
    }

    private static Bitmap scale(Bitmap src) {
        int max = Math.max(src.getWidth(), src.getHeight());
        if (max <= THUMB_SIZE) return src;
        float k = (float) THUMB_SIZE / max;
        return Bitmap.createScaledBitmap(src, Math.round(src.getWidth() * k), Math.round(src.getHeight() * k), true);
    }
}
