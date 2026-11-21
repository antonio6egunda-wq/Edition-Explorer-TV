package com.edition.explorer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/** Página de transferência: QR code à esquerda, controlos à direita e os banners em baixo. */
public class LanShareActivity extends AppCompatActivity {

    private static final long AUTO_OFF_MS = 10L * 60L * 1000L;

    private ImageView ivQr;
    private TextView tvQrHint, tvUrl, tvStatus, tvUpload, tvEnvio, tvNovosSub, tvEnviarSub;
    private Button btnAtivar, btnLimpar;
    private Switch swAuto;
    private View bannerNovos, bannerEnviar, dotNovos;

    private LanShareServer server;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable autoOff = () -> {
        desativar();
        Toast.makeText(this, R.string.lan_auto_desligado, Toast.LENGTH_LONG).show();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lan_share);

        ivQr = findViewById(R.id.ivQrCode);
        tvQrHint = findViewById(R.id.tvQrHint);
        tvUrl = findViewById(R.id.tvUrl);
        tvStatus = findViewById(R.id.tvStatus);
        tvUpload = findViewById(R.id.tvUpload);
        tvEnvio = findViewById(R.id.tvEnvio);
        btnAtivar = findViewById(R.id.btnAtivar);
        btnLimpar = findViewById(R.id.btnLimpar);
        swAuto = findViewById(R.id.swAuto);
        bannerNovos = findViewById(R.id.bannerNovos);
        bannerEnviar = findViewById(R.id.bannerEnviar);

        btnAtivar.setOnClickListener(v -> {
            if (server == null) ativar();
            else desativar();
        });

        btnLimpar.setOnClickListener(v -> {
            SendQueue.clear();
            atualizarEnvio();
        });

        swAuto.setOnCheckedChangeListener((b, on) -> {
            if (server == null) return;
            handler.removeCallbacks(autoOff);
            if (on) handler.postDelayed(autoOff, AUTO_OFF_MS);
        });

        // Banner Novos: abre a pasta Download
        ((ImageView) bannerNovos.findViewById(R.id.ivBannerIcon)).setImageResource(R.drawable.ic_folder_downloads);
        ((TextView) bannerNovos.findViewById(R.id.tvBannerTitle)).setText(R.string.novos);
        tvNovosSub = bannerNovos.findViewById(R.id.tvBannerSub);
        dotNovos = bannerNovos.findViewById(R.id.viewDotNovo);
        bannerNovos.setOnClickListener(v ->
                startActivity(new Intent(this, BrowserActivity.class)
                        .putExtra(BrowserActivity.EXTRA_DIR, SeenStore.downloadsDir().getAbsolutePath())));

        // Banner Enviar da TV: abre o navegador em modo de seleção
        ((ImageView) bannerEnviar.findViewById(R.id.ivBannerIcon)).setImageResource(R.drawable.ic_folder_filled);
        ((TextView) bannerEnviar.findViewById(R.id.tvBannerTitle)).setText(R.string.banner_enviar);
        tvEnviarSub = bannerEnviar.findViewById(R.id.tvBannerSub);
        bannerEnviar.setOnClickListener(v ->
                startActivity(new Intent(this, BrowserActivity.class)
                        .putExtra(BrowserActivity.EXTRA_DIR, Environment.getExternalStorageDirectory().getAbsolutePath())
                        .putExtra(BrowserActivity.EXTRA_SELECT, true)));

        tvStatus.setText(R.string.lan_inativo);
    }

    @Override
    protected void onResume() {
        super.onResume();
        atualizarNovos();
        atualizarEnvio();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (server != null) {
            server.stop();
            server = null;
        }
        super.onDestroy();
    }

    private void atualizarNovos() {
        int n = SeenStore.unseenCount(this);
        dotNovos.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        tvNovosSub.setText(getString(R.string.por_ver, n));
    }

    private void atualizarEnvio() {
        List<File> files = SendQueue.files();
        int n = files.size();
        tvEnviarSub.setText(getString(R.string.selecionados_n, n));
        tvEnvio.setText(n == 0 ? getString(R.string.envio_vazio) : getString(R.string.envio_pronto, n));
        btnLimpar.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        if (server != null) server.setShareFiles(files);
    }

    private void ativar() {
        String ip = obterIpLocal();
        if (ip == null) {
            Toast.makeText(this, R.string.lan_sem_rede, Toast.LENGTH_LONG).show();
            return;
        }

        int porta = LanShareServer.obterPortaAleatoriaLivre();
        String token = LanShareServer.gerarToken();
        String url = "http://" + ip + ":" + porta + "/?t=" + token;

        File destino = SeenStore.downloadsDir();
        destino.mkdirs();

        try {
            server = new LanShareServer(porta, destino, token,
                    nome -> runOnUiThread(() -> onRecebido(nome)));
            server.setShareFiles(SendQueue.files());
            server.start();
        } catch (Exception e) {
            server = null;
            Toast.makeText(this, getString(R.string.lan_erro, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }

        Bitmap qr = gerarQr(url, 480);
        if (qr != null) ivQr.setImageBitmap(qr);
        tvQrHint.setVisibility(View.GONE);
        tvUrl.setText(url);
        tvUrl.setVisibility(View.VISIBLE);
        tvUpload.setText("");
        tvStatus.setText(R.string.lan_ativo);
        btnAtivar.setText(R.string.lan_desativar);

        handler.removeCallbacks(autoOff);
        if (swAuto.isChecked()) handler.postDelayed(autoOff, AUTO_OFF_MS);
    }

    private void desativar() {
        handler.removeCallbacks(autoOff);
        if (server != null) {
            server.stop();
            server = null;
        }
        ivQr.setImageDrawable(null);
        tvQrHint.setVisibility(View.VISIBLE);
        tvUrl.setVisibility(View.GONE);
        tvStatus.setText(R.string.lan_inativo);
        btnAtivar.setText(R.string.lan_ativar);
    }

    private void onRecebido(String nome) {
        tvUpload.setText(getString(R.string.lan_recebido, nome));
        tvUpload.setTextColor(Color.parseColor("#66BB6A"));
        LibraryIndex.invalidate();
        atualizarNovos();
    }

    private String obterIpLocal() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Bitmap gerarQr(String texto, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    pixels[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }
}
