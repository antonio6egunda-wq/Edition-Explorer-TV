package com.edition.explorer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Página de transferência: QR code e código de acesso, lista de envio e banners em baixo. */
public class LanShareActivity extends AppCompatActivity {

    private static final long AUTO_OFF_MS = 10L * 60L * 1000L;

    private ImageView ivQr;
    private TextView tvQrHint, tvStatus, tvUrl, tvCodigo, tvIps, tvUpload;
    private TextView tvEnvioVazio, tvNovosSub, tvEnviarSub;
    private LinearLayout llEnvio;
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
        tvStatus = findViewById(R.id.tvStatus);
        tvUrl = findViewById(R.id.tvUrl);
        tvCodigo = findViewById(R.id.tvCodigo);
        tvIps = findViewById(R.id.tvIps);
        tvUpload = findViewById(R.id.tvUpload);
        tvEnvioVazio = findViewById(R.id.tvEnvioVazio);
        llEnvio = findViewById(R.id.llEnvio);
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

    /** Lista dos ficheiros marcados, com botão de remover em cada um. */
    private void atualizarEnvio() {
        List<File> files = SendQueue.files();
        int n = files.size();

        tvEnviarSub.setText(getString(R.string.selecionados_n, n));
        tvEnvioVazio.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        btnLimpar.setVisibility(n == 0 ? View.GONE : View.VISIBLE);

        llEnvio.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (File f : files) {
            View row = inflater.inflate(R.layout.item_envio, llEnvio, false);
            ((TextView) row.findViewById(R.id.tvNome)).setText(f.getName());
            row.findViewById(R.id.btnRemover).setOnClickListener(v -> {
                SendQueue.toggle(f);
                atualizarEnvio();
            });
            llEnvio.addView(row);
        }

        if (server != null) server.setShareFiles(files);
    }

    private void ativar() {
        List<String> ips = ipsDisponiveis();
        if (ips.isEmpty()) {
            Toast.makeText(this, R.string.lan_sem_rede, Toast.LENGTH_LONG).show();
            return;
        }

        int porta = LanShareServer.escolherPorta();
        String codigo = LanShareServer.gerarToken();
        String base = "http://" + ips.get(0) + ":" + porta;

        File destino = SeenStore.downloadsDir();
        destino.mkdirs();

        try {
            server = new LanShareServer(porta, destino, codigo,
                    nome -> runOnUiThread(() -> onRecebido(nome)));
            server.setShareFiles(SendQueue.files());
            server.start();
        } catch (Exception e) {
            server = null;
            Toast.makeText(this, getString(R.string.lan_erro, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }

        Bitmap qr = gerarQr(base + "/?t=" + codigo, 480);
        if (qr != null) ivQr.setImageBitmap(qr);
        tvQrHint.setVisibility(View.GONE);

        tvUrl.setText(base);
        tvCodigo.setText(codigo);
        if (ips.size() > 1) {
            tvIps.setText(getString(R.string.enderecos_outros, join(ips.subList(1, ips.size()))));
        } else {
            tvIps.setText("");
        }

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
        tvUrl.setText("");
        tvCodigo.setText("");
        tvIps.setText("");
        tvStatus.setText(R.string.lan_inativo);
        btnAtivar.setText(R.string.lan_ativar);
    }

    private void onRecebido(String nome) {
        tvUpload.setText(getString(R.string.lan_recebido, nome));
        tvUpload.setTextColor(Color.parseColor("#66BB6A"));
        LibraryIndex.invalidate();
        atualizarNovos();
    }

    /**
     * Endereços IPv4 da TV. Wi-Fi e Ethernet primeiro; ignora interfaces virtuais
     * (tun, docker, veth, rmnet) e endereços de ligação local (169.254.x.x).
     */
    private List<String> ipsDisponiveis() {
        List<String> prioritarios = new ArrayList<>();
        List<String> outros = new ArrayList<>();
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                String n = intf.getName().toLowerCase(Locale.ROOT);
                if (n.startsWith("tun") || n.startsWith("dummy") || n.startsWith("docker")
                        || n.startsWith("veth") || n.startsWith("rmnet") || n.startsWith("v4-")) continue;
                boolean pri = n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("wifi") || n.startsWith("en");
                for (InetAddress a : Collections.list(intf.getInetAddresses())) {
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("169.254.")) continue;
                    (pri ? prioritarios : outros).add(ip);
                }
            }
        } catch (Exception ignored) {
        }
        prioritarios.addAll(outros);
        return prioritarios;
    }

    private static String join(List<String> itens) {
        StringBuilder sb = new StringBuilder();
        for (String s : itens) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
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
