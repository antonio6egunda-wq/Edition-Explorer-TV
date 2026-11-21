package com.edition.explorer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

/** Ecrã inicial para TV: cartões de armazenamento, banners da biblioteca e acesso à transferência LAN. */
public class HomeActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 100;

    private View cardStorage, cardUsb, cardSd;
    private View bannerVideos, bannerMusica, bannerFotos, bannerDocs;
    private View layoutAviso;
    private Button btnPermissao, btnLanShare;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        cardStorage = findViewById(R.id.cardStorage);
        cardUsb = findViewById(R.id.cardUsb);
        cardSd = findViewById(R.id.cardSd);
        bannerVideos = findViewById(R.id.bannerVideos);
        bannerMusica = findViewById(R.id.bannerMusica);
        bannerFotos = findViewById(R.id.bannerFotos);
        bannerDocs = findViewById(R.id.bannerDocs);
        layoutAviso = findViewById(R.id.layoutAviso);
        btnPermissao = findViewById(R.id.btnPermissao);
        btnLanShare = findViewById(R.id.btnLanShare);

        setupBanner(bannerVideos, R.drawable.ic_shortcut_videos, R.string.banner_videos, LibraryIndex.Category.VIDEO);
        setupBanner(bannerMusica, R.drawable.ic_shortcut_music, R.string.banner_musica, LibraryIndex.Category.AUDIO);
        setupBanner(bannerFotos, R.drawable.ic_shortcut_pictures, R.string.banner_fotos, LibraryIndex.Category.IMAGE);
        setupBanner(bannerDocs, R.drawable.ic_shortcut_documents, R.string.banner_documentos, LibraryIndex.Category.DOCUMENT);

        btnLanShare.setOnClickListener(v -> startActivity(new Intent(this, LanShareActivity.class)));
        btnPermissao.setOnClickListener(v -> pedirPermissao());

        if (!temPermissao()) pedirPermissao();
    }

    @Override
    protected void onResume() {
        super.onResume();
        atualizar();
    }

    private void atualizar() {
        boolean ok = temPermissao();
        layoutAviso.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (!ok) return;

        SeenStore.initIfNeeded(this);
        mostrarVolumes();
        mostrarContagens();
    }

    // ---------- Permissões ----------

    private boolean temPermissao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void pedirPermissao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                atualizar();
            } else {
                Toast.makeText(this, "Permissão de armazenamento negada.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- Cartões de armazenamento ----------

    private void mostrarVolumes() {
        List<StorageHelper.Volume> vols = StorageHelper.getVolumes(this);
        bindCard(cardStorage, StorageHelper.findAll(vols, StorageHelper.TYPE_INTERNAL),
                R.string.card_armazenamento, R.drawable.ic_drive);
        bindCard(cardUsb, StorageHelper.findAll(vols, StorageHelper.TYPE_USB),
                R.string.card_usb, R.drawable.ic_drive);
        bindCard(cardSd, StorageHelper.findAll(vols, StorageHelper.TYPE_SD),
                R.string.card_sd, R.drawable.ic_drive);
    }

    /**
     * Um dispositivo: o card abre-o diretamente.
     * Vários: o card mostra quantos há e, ao carregar, aparece a lista Drive 1, Drive 2...
     */
    private void bindCard(View card, List<StorageHelper.Volume> lista, int titleRes, int iconRes) {
        ImageView icon = card.findViewById(R.id.ivCardIcon);
        TextView title = card.findViewById(R.id.tvCardTitle);
        TextView sub = card.findViewById(R.id.tvCardSub);
        ProgressBar pb = card.findViewById(R.id.pbCard);

        icon.setImageResource(iconRes);
        title.setText(titleRes);

        if (lista.isEmpty()) {
            card.setEnabled(false);
            card.setFocusable(false);
            card.setClickable(false);
            card.setAlpha(0.4f);
            card.setOnClickListener(null);
            pb.setVisibility(View.VISIBLE);
            pb.setProgress(0);
            sub.setText(R.string.nao_inserido);
            return;
        }

        card.setEnabled(true);
        card.setFocusable(true);
        card.setClickable(true);
        card.setAlpha(1f);

        if (lista.size() == 1) {
            StorageHelper.Volume v = lista.get(0);
            long total = v.total();
            long free = v.free();
            int pct = total > 0 ? (int) ((total - free) * 100 / total) : 0;
            pb.setVisibility(View.VISIBLE);
            pb.setProgress(pct);
            sub.setText(getString(R.string.livres_de, FileOps.formatSize(free), FileOps.formatSize(total)));
            card.setOnClickListener(x -> abrirPasta(v.root));
        } else {
            pb.setVisibility(View.GONE);
            sub.setText(getString(R.string.n_dispositivos, lista.size()));
            card.setOnClickListener(x -> escolherDispositivo(titleRes, lista));
        }
    }

    private void escolherDispositivo(int titleRes, List<StorageHelper.Volume> lista) {
        String[] nomes = new String[lista.size()];
        for (int i = 0; i < lista.size(); i++) {
            StorageHelper.Volume v = lista.get(i);
            nomes[i] = v.label + "  ·  " + FileOps.formatSize(v.free()) + " livres";
        }
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setItems(nomes, (d, which) -> abrirPasta(lista.get(which).root))
                .setNegativeButton(R.string.cancelar, null)
                .show();
    }

    // ---------- Banners da biblioteca ----------

    private void setupBanner(View banner, int iconRes, int titleRes, LibraryIndex.Category cat) {
        ((ImageView) banner.findViewById(R.id.ivBannerIcon)).setImageResource(iconRes);
        ((TextView) banner.findViewById(R.id.tvBannerTitle)).setText(titleRes);
        banner.setOnClickListener(v -> abrirCategoria(cat));
    }

    private void mostrarContagens() {
        if (!LibraryIndex.isReady()) {
            setSub(bannerVideos, null);
            setSub(bannerMusica, null);
            setSub(bannerFotos, null);
            setSub(bannerDocs, null);
            LibraryIndex.refresh(this, this::mostrarContagens);
            return;
        }
        setSub(bannerVideos, LibraryIndex.count(LibraryIndex.Category.VIDEO));
        setSub(bannerMusica, LibraryIndex.count(LibraryIndex.Category.AUDIO));
        setSub(bannerFotos, LibraryIndex.count(LibraryIndex.Category.IMAGE));
        setSub(bannerDocs, LibraryIndex.count(LibraryIndex.Category.DOCUMENT));
    }

    private void setSub(View banner, Integer count) {
        TextView t = banner.findViewById(R.id.tvBannerSub);
        t.setText(count == null ? getString(R.string.a_calcular) : getString(R.string.itens_count, count));
    }

    // ---------- Navegação ----------

    private void abrirPasta(java.io.File root) {
        startActivity(new Intent(this, BrowserActivity.class)
                .putExtra(BrowserActivity.EXTRA_DIR, root.getAbsolutePath()));
    }

    private void abrirCategoria(LibraryIndex.Category c) {
        startActivity(new Intent(this, BrowserActivity.class)
                .putExtra(BrowserActivity.EXTRA_CATEGORY, c.name()));
    }
}
