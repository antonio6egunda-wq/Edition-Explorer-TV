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

    private TextView tvTitle, tvEmpty, tvClipboard;
    private Button btnDetalhado, btnCompacto, btnGrade, btnOrdenar, btnEnviar;
    private RecyclerView rv;
    private FileAdapter adapter;

    private final FileAdapter.Callback callback = new FileAdapter.Callback() {
        @Override
        public void onOpen(File f) {
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
        btnDetalhado = findViewById(R.id.btnDetalhado);
        btnCompacto = findViewById(R.id.btnCompacto);
        btnGrade = findViewById(R.id.btnGrade);
        btnOrdenar = findViewById(R.id.btnOrdenar);
        btnEnviar = findViewById(R.id.btnEnviar);
        rv = findViewById(R.id.rvFiles);

        String cat = getIntent().getStringExtra(EXTRA_CATEGORY);
        String path = getIntent().getStringExtra(EXTRA_DIR);
        selectMode = getIntent().getBooleanExtra(EXTRA_SELECT, false);
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

        // No modo de seleção, o botão Enviar substitui o de ordenação
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
        rv.setLayoutManager(mode == MODE_GRID ? new GridLayoutManager(this, 5) : new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        btnDetalhado.setActivated(mode == MODE_DETAILED);
        btnCompacto.setActivated(mode == MODE_COMPACT);
        btnGrade.setActivated(mode == MODE_GRID);
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
        }
        Collections.sort(list, comparator());
        adapter.setItems(list);

        if (erro != null) {
            tvEmpty.setText(erro);
            tvEmpty.setVisibility(View.VISIBLE);
        } else if (list.isEmpty()) {
            tvEmpty.setText(R.string.pasta_vazia);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
        atualizarClipboard();
    }

    /** Pastas primeiro (só nas pastas reais); depois por nome ou por data, mais recente primeiro. */
    private Comparator<File> comparator() {
        final boolean porData = sort == SORT_DATE;
        return (a, b) -> {
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

        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);

            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime != null ? mime : "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Toast.makeText(this, R.string.nenhuma_app, Toast.LENGTH_SHORT).show();
        }
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
            if (f != null) mostrarMenu(f);
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
        View v = LayoutInflater.from(this).inflate(R.layout.layout_menu_acao, null);
        PopupWindow pw = new PopupWindow(v, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setElevation(24f);

        View colar = v.findViewById(R.id.btn_colar);
        boolean podeColar = dir != null && clipFile != null;
        colar.setEnabled(podeColar);
        colar.setAlpha(podeColar ? 1f : 0.4f);

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

    private void colar() {
        if (clipFile == null || dir == null) return;
        final File src = clipFile;
        final boolean cut = clipCut;
        final File destino = dir;
        runOp(cut ? R.string.ok_movido : R.string.ok_copiado, () -> {
            boolean ok = cut ? FileOps.move(src, destino) : FileOps.copy(src, destino);
            if (ok && cut) clipFile = null;
            return ok;
        });
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
                    runOp(R.string.ok_renomeado, () -> FileOps.rename(f, novo));
                })
                .setNegativeButton(R.string.cancelar, null)
                .show();
    }

    private void confirmarExcluir(File f) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.excluir_titulo)
                .setMessage(getString(R.string.excluir_msg, f.getName()))
                .setPositiveButton(R.string.excluir, (d, w) -> runOp(R.string.ok_excluido, () -> FileOps.delete(f)))
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

    private void atualizarClipboard() {
        if (clipFile == null) {
            tvClipboard.setVisibility(View.GONE);
            return;
        }
        tvClipboard.setText(getString(clipCut ? R.string.clip_recortado : R.string.clip_copiado, clipFile.getName()));
        tvClipboard.setVisibility(View.VISIBLE);
    }

    /** Corre uma operação de ficheiros fora da UI e atualiza a lista no fim. */
    private void runOp(int msgOk, Op op) {
        new Thread(() -> {
            boolean ok;
            try {
                ok = op.run();
            } catch (Exception e) {
                ok = false;
            }
            final boolean resultado = ok;
            runOnUiThread(() -> {
                Toast.makeText(this, resultado ? getString(msgOk) : getString(R.string.erro_operacao),
                        Toast.LENGTH_SHORT).show();
                LibraryIndex.invalidate();
                reload();
            });
        }).start();
    }
}
