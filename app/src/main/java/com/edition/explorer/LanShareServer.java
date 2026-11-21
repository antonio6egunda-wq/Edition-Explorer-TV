package com.edition.explorer;

import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Servidor local com duas funções, sempre protegido pelo token do QR code:
 * - receber ficheiros enviados do telemóvel (POST) para a pasta Download;
 * - oferecer para descarregar os ficheiros marcados na TV (/download/N).
 */
public class LanShareServer extends NanoHTTPD {

    public interface OnFileUploadedListener {
        void onFileUploaded(String fileName);
    }

    private final File targetDir;
    private final String token;
    private final OnFileUploadedListener listener;
    private volatile List<File> shareFiles = Collections.emptyList();

    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String DOWNLOAD_PREFIX = "/download/";

    public static int obterPortaAleatoriaLivre() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 8899;
        }
    }

    /** Token aleatório de 10 caracteres: só quem lê o QR code acede aos ficheiros. */
    public static String gerarToken() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(ALFABETO.charAt(rnd.nextInt(ALFABETO.length())));
        return sb.toString();
    }

    public LanShareServer(int port, File targetDir, String token, OnFileUploadedListener listener) {
        super(port);
        this.targetDir = targetDir;
        this.token = token;
        this.listener = listener;
    }

    /** Define os ficheiros disponíveis para descarregar. Pode ser chamado com o servidor ativo. */
    public void setShareFiles(List<File> files) {
        shareFiles = new ArrayList<>(files);
    }

    @Override
    public Response serve(IHTTPSession session) {
        List<String> t = session.getParameters().get("t");
        if (t == null || t.isEmpty() || !token.equals(t.get(0))) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain; charset=utf-8",
                    "Acesso negado. Leia o código QR no ecrã da TV.");
        }
        if (Method.POST.equals(session.getMethod())) {
            return receberFicheiro(session);
        }
        String uri = session.getUri();
        if (uri != null && uri.startsWith(DOWNLOAD_PREFIX)) {
            return enviarFicheiro(uri.substring(DOWNLOAD_PREFIX.length()));
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", gerarPaginaWeb());
    }

    // ---------- Receber do telemóvel ----------

    private Response receberFicheiro(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);

            String tempFilePath = files.get("file");
            if (tempFilePath == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Nenhum ficheiro recebido");
            }

            List<String> nomes = session.getParameters().get("file");
            String originalName = (nomes != null && !nomes.isEmpty() && !nomes.get(0).isEmpty())
                    ? nomes.get(0)
                    : "ficheiro_" + System.currentTimeMillis();

            // Impede path traversal (ex.: ../../algo) mantendo só o nome do ficheiro
            File destFile = escolherNomeLivre(new File(originalName).getName());

            try (InputStream in = new FileInputStream(tempFilePath);
                 OutputStream out = new FileOutputStream(destFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }

            if (listener != null) {
                listener.onFileUploaded(destFile.getName());
            }
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK");
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Erro: " + e.getMessage());
        }
    }

    /** Evita sobrescrever: "foto.jpg" -> "foto (1).jpg" se já existir. */
    private File escolherNomeLivre(String nome) {
        File dest = new File(targetDir, nome);
        if (!dest.exists()) return dest;

        String base = nome;
        String ext = "";
        int dot = nome.lastIndexOf('.');
        if (dot > 0) {
            base = nome.substring(0, dot);
            ext = nome.substring(dot);
        }
        int i = 1;
        while (dest.exists()) {
            dest = new File(targetDir, base + " (" + i++ + ")" + ext);
        }
        return dest;
    }

    // ---------- Enviar para o telemóvel ----------

    private Response enviarFicheiro(String indice) {
        int i;
        try {
            i = Integer.parseInt(indice);
        } catch (NumberFormatException e) {
            return naoEncontrado();
        }
        List<File> list = shareFiles;
        if (i < 0 || i >= list.size()) return naoEncontrado();

        File f = list.get(i);
        if (!f.isFile() || !f.canRead()) return naoEncontrado();

        try {
            InputStream in = new FileInputStream(f);
            Response r = newFixedLengthResponse(Response.Status.OK, mimeOf(f), in, f.length());
            r.addHeader("Content-Disposition",
                    "attachment; filename=\"" + nomeAscii(f.getName()) + "\"; filename*=UTF-8''" + codificar(f.getName()));
            return r;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Erro ao ler o ficheiro");
        }
    }

    private Response naoEncontrado() {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Ficheiro não encontrado");
    }

    private static String mimeOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    private static String nomeAscii(String name) {
        return name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
    }

    private static String codificar(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    // ---------- Página web ----------

    private String gerarPaginaWeb() {
        return PAGINA.replace("{{NOME}}", escaparHtml(targetDir.getName()))
                .replace("{{DOWNLOADS}}", gerarListaDownloads());
    }

    private String gerarListaDownloads() {
        List<File> list = shareFiles;
        if (list.isEmpty()) {
            return "<p class=\"empty\">Nenhum ficheiro selecionado na TV.</p>";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            File f = list.get(i);
            String nome = escaparHtml(f.getName());
            sb.append("<div class=\"item\">")
                    .append("<div class=\"ico\">").append(iconeDe(f)).append("</div>")
                    .append("<div class=\"meta\">")
                    .append("<div class=\"name\">").append(nome).append("</div>")
                    .append("<div class=\"size\">").append(FileOps.formatSize(f.length())).append("</div>")
                    .append("<div class=\"bar small\"><i></i></div>")
                    .append("</div>")
                    .append("<button class=\"btn btn-sm\" data-url=\"/download/").append(i)
                    .append("?t=").append(token).append("\" data-name=\"").append(nome)
                    .append("\" data-size=\"").append(f.length()).append("\">Descarregar</button>")
                    .append("</div>");
        }
        return sb.toString();
    }

    private static String iconeDe(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")
                || n.endsWith(".webm") || n.endsWith(".ts") || n.endsWith(".m4v") || n.endsWith(".3gp")) return "🎬";
        if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".ogg")
                || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".opus")) return "🎵";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                || n.endsWith(".gif") || n.endsWith(".bmp")) return "🖼️";
        if (n.endsWith(".zip") || n.endsWith(".apk")) return "📦";
        return "📄";
    }

    private static String escaparHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static final String PAGINA = """
            <!DOCTYPE html>
            <html lang="pt"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Edition Explorer</title>
            <style>
            :root{--bg:#0b1020;--card:#151c2e;--line:#243047;--acc:#4fc3f7;--acc2:#2979ff;--txt:#eef2f8;--mut:#8b96ab;--ok:#66bb6a;--err:#ef5350;}
            *{box-sizing:border-box}
            body{margin:0;min-height:100vh;font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;color:var(--txt);
              background:radial-gradient(900px 500px at 10% -10%,#17305c 0%,transparent 60%),var(--bg);
              display:flex;justify-content:center;padding:20px}
            .wrap{width:100%;max-width:480px;display:flex;flex-direction:column;gap:16px;padding-top:8px}
            header h1{margin:0;font-size:24px;font-weight:700;color:var(--acc)}
            header p{margin:4px 0 0;color:var(--mut);font-size:14px}
            .card{background:var(--card);border:1px solid var(--line);border-radius:24px;padding:22px}
            .card h2{margin:0 0 14px;font-size:16px;font-weight:600}
            .drop{display:flex;flex-direction:column;align-items:center;gap:8px;border:1.5px dashed var(--acc);
              border-radius:20px;padding:26px 16px;text-align:center;color:var(--mut);font-size:15px;cursor:pointer;background:#0f1626}
            .big{width:52px;height:52px;border-radius:50%;display:flex;align-items:center;justify-content:center;
              font-size:28px;color:#fff;background:linear-gradient(135deg,var(--acc2),var(--acc))}
            .btn{display:block;width:100%;border:0;border-radius:999px;padding:14px 20px;font-size:16px;font-weight:600;
              color:#fff;background:linear-gradient(135deg,var(--acc2),var(--acc));text-align:center;text-decoration:none;
              margin-top:14px;cursor:pointer;box-shadow:0 8px 20px -8px rgba(41,121,255,.6)}
            .btn:disabled{opacity:.45;box-shadow:none;cursor:default}
            .btn-sm{display:inline-block;width:auto;margin:0;padding:9px 16px;font-size:14px;box-shadow:none;white-space:nowrap}
            .bar{display:none;height:8px;border-radius:999px;background:#0f1626;overflow:hidden;margin-top:16px}
            .bar i{display:block;height:100%;width:0;background:linear-gradient(90deg,var(--acc2),var(--acc));transition:width .2s}
            #status{margin-top:12px;font-size:14px;text-align:center;min-height:18px;color:var(--mut)}
            .item{display:flex;align-items:center;gap:12px;padding:12px;border-radius:18px;background:#0f1626;
              border:1px solid var(--line);margin-bottom:10px}
            .item:last-child{margin-bottom:0}
            .ico{width:44px;height:44px;flex:0 0 44px;border-radius:14px;background:#1a2440;display:flex;
              align-items:center;justify-content:center;font-size:22px}
            .meta{flex:1;min-width:0}
            .name{font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            .size{font-size:12px;color:var(--mut);margin-top:2px}
            .empty{color:var(--mut);font-size:14px;text-align:center;margin:8px 0}
            .bar.small{height:6px;margin-top:8px}
            </style></head><body>
            <div class="wrap">
              <header>
                <h1>Edition Explorer</h1>
                <p>TV Box · {{NOME}}</p>
              </header>

              <section class="card">
                <h2>Enviar para a TV</h2>
                <label class="drop" for="fileInput">
                  <div class="big">+</div>
                  <div id="fileLabel">Toque para escolher ficheiros</div>
                </label>
                <input type="file" id="fileInput" multiple hidden>
                <button class="btn" id="btnEnviar" disabled>Enviar para a TV</button>
                <div class="bar" id="pWrap"><i id="pBar"></i></div>
                <div id="status"></div>
              </section>

              <section class="card">
                <h2>Descarregar da TV</h2>
                {{DOWNLOADS}}
              </section>
            </div>
            <script>
            var input=document.getElementById('fileInput');
            var btn=document.getElementById('btnEnviar');
            var label=document.getElementById('fileLabel');
            var pWrap=document.getElementById('pWrap');
            var pBar=document.getElementById('pBar');
            var statusEl=document.getElementById('status');
            input.addEventListener('change',function(){
              var n=input.files.length;
              label.innerText=n>0?n+' ficheiro(s) selecionado(s)':'Toque para escolher ficheiros';
              btn.disabled=n===0;
              statusEl.innerText='';
            });
            btn.addEventListener('click',enviarFicheiros);
            async function enviarFicheiros(){
              var files=input.files;
              if(!files.length)return;
              btn.disabled=true;
              pWrap.style.display='block';
              var falhas=0;
              for(var i=0;i<files.length;i++){
                statusEl.style.color='#8b96ab';
                statusEl.innerText='A enviar ('+(i+1)+'/'+files.length+'): '+files[i].name;
                pBar.style.width='0%';
                await new Promise(function(resolve){
                  var xhr=new XMLHttpRequest();
                  var fd=new FormData();
                  fd.append('file',files[i]);
                  xhr.upload.onprogress=function(e){if(e.lengthComputable){pBar.style.width=Math.round(e.loaded/e.total*100)+'%';}};
                  xhr.onload=function(){if(xhr.status!==200)falhas++;resolve();};
                  xhr.onerror=function(){falhas++;resolve();};
                  xhr.open('POST',location.pathname+location.search);
                  xhr.send(fd);
                });
              }
              if(falhas===0){
                pBar.style.width='100%';
                statusEl.style.color='#66bb6a';
                statusEl.innerText='Envio concluído com sucesso!';
              }else{
                statusEl.style.color='#ef5350';
                statusEl.innerText=falhas+' ficheiro(s) falharam.';
              }
              input.value='';
              label.innerText='Toque para escolher ficheiros';
              btn.disabled=true;
            }
            </script>
            <script>
            var LIMITE_BLOB=1500*1024*1024;
            document.querySelectorAll('[data-url]').forEach(function(btn){
              btn.addEventListener('click',function(){baixar(btn);});
            });
            function baixar(btn){
              var url=btn.getAttribute('data-url');
              var nome=btn.getAttribute('data-name');
              var tamanho=parseInt(btn.getAttribute('data-size'),10)||0;
              if(tamanho>LIMITE_BLOB){window.location.href=url;return;}
              var item=btn.closest('.item');
              var pb=item.querySelector('.bar');
              var pi=pb.querySelector('i');
              btn.disabled=true;
              btn.innerText='0%';
              pb.style.display='block';
              var xhr=new XMLHttpRequest();
              xhr.open('GET',url);
              xhr.responseType='blob';
              xhr.onprogress=function(e){
                if(e.lengthComputable){
                  var p=Math.round(e.loaded/e.total*100);
                  pi.style.width=p+'%';
                  btn.innerText=p+'%';
                }
              };
              xhr.onload=function(){
                if(xhr.status===200){
                  var blobUrl=URL.createObjectURL(xhr.response);
                  var a=document.createElement('a');
                  a.href=blobUrl;
                  a.download=nome;
                  document.body.appendChild(a);
                  a.click();
                  a.remove();
                  setTimeout(function(){URL.revokeObjectURL(blobUrl);},60000);
                  pi.style.width='100%';
                  btn.innerText='Concluído';
                }else{
                  btn.innerText='Erro';
                }
                finalizar(btn,pb);
              };
              xhr.onerror=function(){
                btn.innerText='Erro';
                finalizar(btn,pb);
              };
              xhr.send();
            }
            function finalizar(btn,pb){
              setTimeout(function(){
                btn.innerText='Descarregar';
                btn.disabled=false;
                pb.style.display='none';
                pb.querySelector('i').style.width='0%';
              },2500);
            }
            </script></body></html>
            """;
}
