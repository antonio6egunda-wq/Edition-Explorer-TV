package com.edition.explorer;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Navegador de ficheiros: pasta real, categoria, ou modo de seleção para enviar ao telemóvel. */
public class BrowserActivity extends AppCompatActivity {

    public static final String EXTRA_DIR = "dir";
    public static final String EXTRA_CATEGORY = "category";
    public static final String EXTRA_SELECT = "select";
    public static final String EXTRA_FOCUS = "focus";

    public static final int MODE_DETAILED = 0;
    public static final int MODE_COMPACT = 1;
    public static final int MODE_GRID = 2;

    private static final int SORT_NAME = 0;
    private static final int SORT_DATE = 1;
    private static final String PREFS = "browser";
    private static final String KEY_MODE = "mode";
    private static final String KEY_SORT = "sort";

    // Área de transferência em memória, partilhada entre ecrãs
    private static File clipFile;
    private static boolean clipCut;

    private interface Op {
        boolean run() throws Exception;
    }

    private File dir;
    private LibraryIndex.Category category;
    private boolean selectMode;
    private int mode;
    private int sort;

    // Foco: só é aplicado ao abrir o ecrã, para não saltar ao voltar de um menu
    private String focusNome;
    private boolean focarAoCarregar = true;

    // Impede operações sobrepostas (era o que duplicava os ficheiros colados)
    private boolean operando;

    private TextView tvTitle, tvEmpty, tvClipboard;
    private View layoutClipboard;
    private Button btnColarAqui, btnCancelarClip;
    private Button btnDetalhado, btnCompacto, btnGrade, btnOrdenar, btnEnviar;
    private View layoutCabecalho;
    private RecyclerView rv;
    private FileAdapter adapter;

    private final FileAdapter.Callback callback = new FileAdapter.Callback() {
        @Override
        public void onOpen(File f) {
            if (FileAdapter.isParent(f)) {
                subir();
                return;
            }
            if (selectMode && !f.isDirectory()) {
                SendQueue.toggle(f);
                adapter.notifyDataSetChanged();
                atualizarEnviar();
                return;
            }
            abrir(f);
        }

        @Override
        public void onLongPress(File f) {
            mostrarMenu(f);
        }

        @Override
        public boolean isNew(File f) {
            return SeenStore.isNew(BrowserActivity.this, f);
        }

        @Override
        public boolean isSelected(File f) {
            return SendQueue.contains(f);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        tvTitle = findViewById(R.id.tvTitle);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvClipboard = findViewById(R.id.tvClipboard);
        layoutClipboard = findViewById(R.id.layoutClipboard);
        btnColarAqui = findViewById(R.id.btnColarAqui);
        btnCancelarClip = findViewById(R.id.btnCancelarClip);
        btnColarAqui.setOnClickListener(v -> colar());
        btnCancelarClip.setOnClickListener(v -> {
            clipFile = null;
            clipCut = false;
            atualizarClipboard();
        });
        btnDetalhado = findViewById(R.id.btnDetalhado);
        btnCompacto = findViewById(R.id.btnCompacto);
        btnGrade = findViewById(R.id.btnGrade);
        btnOrdenar = findViewById(R.id.btnOrdenar);
        btnEnviar = findViewById(R.id.btnEnviar);
        layoutCabecalho = findViewById(R.id.layoutCabecalho);
        rv = findViewById(R.id.rvFiles);

        String cat = getIntent().getStringExtra(EXTRA_CATEGORY);
        String path = getIntent().getStringExtra(EXTRA_DIR);
        selectMode = getIntent().getBooleanExtra(EXTRA_SELECT, false);
        focusNome = getIntent().getStringExtra(EXTRA_FOCUS);
        if (cat != null) {
            category = LibraryIndex.Category.valueOf(cat);
        } else {
            dir = new File(path != null ? path : "/storage/emulated/0");
        }

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        mode = p.getInt(KEY_MODE, MODE_DETAILED);
        sort = p.getInt(KEY_SORT, SORT_NAME);

        btnDetalhado.setOnClickListener(v -> setMode(MODE_DETAILED));
        btnCompacto.setOnClickListener(v -> setMode(MODE_COMPACT));
        btnGrade.setOnClickListener(v -> setMode(MODE_GRID));
        btnOrdenar.setOnClickListener(v -> {
            sort = sort == SORT_NAME ? SORT_DATE : SORT_NAME;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_SORT, sort).apply();
            reload();
        });
        btnEnviar.setOnClickListener(v -> voltarParaEnvio());

        btnOrdenar.setVisibility(selectMode ? View.GONE : View.VISIBLE);
        btnEnviar.setVisibility(selectMode ? View.VISIBLE : View.GONE);

        applyLayout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void setMode(int m) {
        mode = m;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_MODE, m).apply();
        applyLayout();
        reload();
    }

    private void applyLayout() {
        adapter = new FileAdapter(this, mode, selectMode, callback);
        if (mode == MODE_GRID) {
            rv.setLayoutManager(new GridLayoutManager(this, colunasGrade()));
        } else {
            rv.setLayoutManager(new LinearLayoutManager(this));
        }
        rv.setAdapter(adapter);
        layoutCabecalho.setVisibility(mode == MODE_COMPACT ? View.VISIBLE : View.GONE);
        btnDetalhado.setActivated(mode == MODE_DETAILED);
        btnCompacto.setActivated(mode == MODE_COMPACT);
        btnGrade.setActivated(mode == MODE_GRID);
    }

    /** Cartões de ~260 dp: 5 colunas num 1080p, mais em ecrãs maiores. */
    private int colunasGrade() {
        float d = getResources().getDisplayMetrics().density;
        int largura = getResources().getDisplayMetrics().widthPixels;
        return Math.max(3, largura / (int) (260 * d));
    }

    private void reload() {
        btnOrdenar.setText(sort == SORT_NAME ? R.string.ordenar_nome : R.string.ordenar_data);
        atualizarEnviar();

        if (dir != null) {
            tvTitle.setText(dir.getAbsolutePath());
        } else {
            tvTitle.setText(tituloCategoria(category));
        }

        if (category != null && !LibraryIndex.isReady()) {
            tvEmpty.setText(R.string.a_procurar);
            tvEmpty.setVisibility(View.VISIBLE);
            LibraryIndex.refresh(this, this::reload);
            return;
        }

        List<File> list = new ArrayList<>();
        String erro = null;
        if (category != null) {
            list.addAll(LibraryIndex.items(category));
        } else {
            File[] fs = dir.listFiles();
            if (fs == null) {
                erro = getString(R.string.sem_acesso);
            } else {
                for (File f : fs) {
                    if (!f.getName().startsWith(".")) list.add(f);
                }
            }
            // ".." aparece em todas as pastas, exceto na raiz de cada armazenamento
            if (!FileAdapter.estaNaRaizDeArmazenamento(dir)) {
                list.add(new File(dir, ".."));
            }
        }
        Collections.sort(list, comparator());
        adapter.setItems(list);

        boolean semFicheiros = true;
        for (File f : list) {
            if (!FileAdapter.isParent(f)) {
                semFicheiros = false;
                break;
            }
        }
        if (erro != null) {
            tvEmpty.setText(erro);
            tvEmpty.setVisibility(View.VISIBLE);
        } else if (semFicheiros) {
            tvEmpty.setText(R.string.pasta_vazia);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }

        if (focarAoCarregar) {
            focarAoCarregar = false;
            focarItem(focusNome);
            focusNome = null;
        }
        atualizarClipboard();
    }

    /** Foca a entrada pedida (ou a primeira) e rola a lista até ela. Numa pasta vazia foca a lista. */
    private void focarItem(String nome) {
        rv.post(() -> {
            if (adapter.getItemCount() == 0) {
                rv.requestFocus();
                return;
            }
            int pos = 0;
            if (nome != null) {
                for (int i = 0; i < adapter.getItemCount(); i++) {
                    if (adapter.getItem(i).getName().equals(nome)) {
                        pos = i;
                        break;
                    }
                }
            }
            final int alvo = pos;
            rv.scrollToPosition(alvo);
            rv.postDelayed(() -> {
                RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(alvo);
                if (vh != null) vh.itemView.requestFocus();
                else rv.requestFocus();
            }, 80);
        });
    }

    /** Pastas primeiro (exceto ".." que fica sempre no topo); depois por nome ou data (mais recente primeiro). */
    private Comparator<File> comparator() {
        final boolean porData = sort == SORT_DATE;
        return (a, b) -> {
            boolean pa = FileAdapter.isParent(a);
            boolean pb = FileAdapter.isParent(b);
            if (pa != pb) return pa ? -1 : 1;
            if (category == null && a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            if (porData) return Long.compare(b.lastModified(), a.lastModified());
            return a.getName().compareToIgnoreCase(b.getName());
        };
    }

    private int tituloCategoria(LibraryIndex.Category c) {
        switch (c) {
            case VIDEO:
                return R.string.banner_videos;
            case AUDIO:
                return R.string.banner_musica;
            case IMAGE:
                return R.string.banner_fotos;
            default:
                return R.string.banner_documentos;
        }
    }

    private void abrir(File f) {
        if (f.isDirectory()) {
            startActivity(new Intent(this, BrowserActivity.class)
                    .putExtra(EXTRA_DIR, f.getAbsolutePath())
                    .putExtra(EXTRA_SELECT, selectMode));
            return;
        }
        SeenStore.markSeen(this, f);
        adapter.notifyDataSetChanged();

        if (FileOps.ehArquivo(f)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.descompactar_titulo)
                    .setMessage(getString(R.string.descompactar_confirma, f.getName()))
                    .setPositiveButton(R.string.descompactar, (d, w) -> descompactar(f))
                    .setNegativeButton(R.string.cancelar, null)
                    .show();
            return;
        }

        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);

            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime != null ? mime : "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(i, getString(R.string.abrir_com)));
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Toast.makeText(this, R.string.nenhuma_app, Toast.LENGTH_SHORT).show();
        }
    }

    /** Sobe para a pasta pai e deixa o foco na pasta de onde se veio. */
    private void subir() {
        File pai = dir != null ? dir.getParentFile() : null;
        if (pai == null) {
            finish();
            return;
        }
        startActivity(new Intent(this, BrowserActivity.class)
                .putExtra(EXTRA_DIR, pai.getAbsolutePath())
                .putExtra(EXTRA_SELECT, selectMode)
                .putExtra(EXTRA_FOCUS, dir.getName()));
        finish();
    }

    // ---------- Modo de seleção para enviar ----------

    private void atualizarEnviar() {
        int n = SendQueue.count();
        btnEnviar.setText(getString(R.string.enviar_n, n));
        btnEnviar.setEnabled(n > 0);
        btnEnviar.setAlpha(n > 0 ? 1f : 0.4f);
    }

    /** Volta à página de transferência, limpando as pastas abertas por cima dela. */
    private void voltarParaEnvio() {
        startActivity(new Intent(this, LanShareActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    // ---------- Tecla Menu / botão Y do comando ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            File f = ficheiroFocado();
            if (f != null && !FileAdapter.isParent(f)) mostrarMenu(f);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private File ficheiroFocado() {
        View focused = rv.getFocusedChild();
        if (focused == null) return null;
        int pos = rv.getChildAdapterPosition(focused);
        return pos == RecyclerView.NO_POSITION ? null : adapter.getItem(pos);
    }

    // ---------- Menu de ações (layout_menu_acao) ----------

    private void mostrarMenu(File f) {
        if (FileAdapter.isParent(f)) return;

        View v = LayoutInflater.from(this).inflate(R.layout.layout_menu_acao, null);
        PopupWindow pw = new PopupWindow(v, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setElevation(24f);

        View colar = v.findViewById(R.id.btn_colar);
        boolean podeColar = dir != null && clipFile != null && !operando;
        colar.setEnabled(podeColar);
        colar.setAlpha(podeColar ? 1f : 0.4f);

        View descompactar = v.findViewById(R.id.btn_descompactar);
        boolean podeDescompactar = FileOps.ehArquivo(f);
        descompactar.setEnabled(podeDescompactar);
        descompactar.setAlpha(podeDescompactar ? 1f : 0.4f);

        v.findViewById(R.id.btn_copiar).setOnClickListener(x -> {
            clipFile = f;
            clipCut = false;
            pw.dismiss();
            atualizarClipboard();
        });
        v.findViewById(R.id.btn_recortar).setOnClickListener(x -> {
            clipFile = f;
            clipCut = true;
            pw.dismiss();
            atualizarClipboard();
        });
        colar.setOnClickListener(x -> {
            pw.dismiss();
            colar();
        });
        v.findViewById(R.id.btn_compactar).setOnClickListener(x -> {
            pw.dismiss();
            compactar(f);
        });
        descompactar.setOnClickListener(x -> {
            pw.dismiss();
            descompactar(f);
        });
        v.findViewById(R.id.btn_renomear).setOnClickListener(x -> {
            pw.dismiss();
            renomear(f);
        });
        v.findViewById(R.id.btn_excluir).setOnClickListener(x -> {
            pw.dismiss();
            confirmarExcluir(f);
        });
        v.findViewById(R.id.btn_informacao).setOnClickListener(x -> {
            pw.dismiss();
            mostrarInfo(f);
        });

        pw.showAtLocation(rv, Gravity.CENTER, 0, 0);
        v.findViewById(R.id.btn_copiar).requestFocus();
    }

    /** Cola uma vez e limpa a área de transferência, para não repetir por engano. */
    private void colar() {
        if (clipFile == null || dir == null || operando) return;
        final File src = clipFile;
        final boolean cut = clipCut;
        final File destino = dir;
        if (src.isDirectory() && (destino.getAbsolutePath().equals(src.getAbsolutePath())
                || destino.getAbsolutePath().startsWith(src.getAbsolutePath() + "/"))) {
            Toast.makeText(this, R.string.colar_bloqueado, Toast.LENGTH_LONG).show();
            return;
        }
        runOp(cut ? R.string.ok_movido : R.string.ok_copiado,
                () -> cut ? FileOps.move(src, destino) : FileOps.copy(src, destino),
                true);
    }

    private void compactar(File f) {
        final File alvo = f;
        final File destino = dir != null ? dir : f.getParentFile();
        final String nomeZip = f.getName() + ".zip";
        runOp(R.string.ok_compactado, () -> {
            FileOps.zipar(Collections.singletonList(alvo), destino, nomeZip);
            return true;
        }, false);
    }

    private void descompactar(File f) {
        final File alvo = f;
        runOp(R.string.ok_descompactado, () -> {
            FileOps.descompactar(alvo, alvo.getParentFile());
            return true;
        }, false);
    }

    private void renomear(File f) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(f.getName());
        new AlertDialog.Builder(this)
                .setTitle(R.string.renomear)
                .setView(et)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    final String novo = et.getText().toString().trim();
                    if (novo.isEmpty() || novo.equals(f.getName())) return;
                    runOp(R.string.ok_renomeado, () -> FileOps.rename(f, novo), false);
                })
                .setNegativeButton(R.string.cancelar, null)
                .show();
    }

    private void confirmarExcluir(File f) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.excluir_titulo)
                .setMessage(getString(R.string.excluir_msg, f.getName()))
                .setPositiveButton(R.string.excluir, (d, w) ->
                        runOp(R.string.ok_excluido, () -> FileOps.delete(f), false))
                .setNegativeButton(R.string.cancelar, null)
                .show();
    }

    private void mostrarInfo(File f) {
        String tipo;
        if (f.isDirectory()) {
            tipo = getString(R.string.tipo_pasta);
        } else {
            int dot = f.getName().lastIndexOf('.');
            String ext = dot >= 0 ? f.getName().substring(dot + 1) : "";
            tipo = getString(R.string.tipo_ficheiro) + (ext.isEmpty() ? "" : " (." + ext + ")");
        }
        String data = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                .format(new Date(f.lastModified()));
        String tamanho = f.isDirectory() ? "—" : FileOps.formatSize(f.length());

        String msg = getString(R.string.tamanho) + ": " + tamanho + "\n"
                + getString(R.string.tipo) + ": " + tipo + "\n"
                + getString(R.string.modificado) + ": " + data + "\n"
                + getString(R.string.caminho) + ": " + f.getAbsolutePath();

        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /** Barra com o que está copiado/recortado, e os botões Colar aqui e Cancelar. */
    private void atualizarClipboard() {
        if (clipFile == null) {
            layoutClipboard.setVisibility(View.GONE);
            return;
        }
        tvClipboard.setText(getString(clipCut ? R.string.clip_recortado : R.string.clip_copiado, clipFile.getName()));
        boolean podeColar = dir != null && !operando;
        btnColarAqui.setEnabled(podeColar);
        btnColarAqui.setAlpha(podeColar ? 1f : 0.4f);
        layoutClipboard.setVisibility(View.VISIBLE);
    }

    /**
     * Corre uma operação de ficheiros fora da UI. Só uma de cada vez.
     * limparClip: limpa a área de transferência quando a operação termina com sucesso.
     */
    private void runOp(int msgOk, Op op, boolean limparClip) {
        if (operando) {
            Toast.makeText(this, R.string.aguarde, Toast.LENGTH_SHORT).show();
            return;
        }
        operando = true;
        new Thread(() -> {
            boolean ok;
            String detalhe = null;
            try {
                ok = op.run();
            } catch (Exception e) {
                ok = false;
                detalhe = e.getMessage();
            }
            final boolean resultado = ok;
            final String causa = detalhe;
            runOnUiThread(() -> {
                operando = false;
                if (resultado && limparClip) {
                    clipFile = null;
                    clipCut = false;
                }
                String texto;
                if (resultado) texto = getString(msgOk);
                else if (causa != null && !causa.isEmpty()) texto = causa;
                else texto = getString(R.string.erro_operacao);
                Toast.makeText(this, texto, Toast.LENGTH_LONG).show();
                LibraryIndex.invalidate();
                reload();
            });
        }).start();
    }
}
