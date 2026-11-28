# Edition Explorer

Explorador de ficheiros para **Android TV** e TV Box, com controlo pelo comando remoto.
Além de navegar pelos ficheiros do aparelho, permite **enviar e receber ficheiros** do telemóvel ou do computador através da rede local, lendo um código QR.

---

## Funcionalidades

### Ecrã inicial
- **Armazenamento:** cartões com a memória usada e o espaço livre de cada volume.
  - *Armazenamento* (memória interna)
  - *USB*, quando existe um pen ou disco ligado. Com vários dispositivos (ex.: hub com várias portas), o card mostra o número de dispositivos e, ao carregar, aparece a lista "Drive 1", "Drive 2"...
  - *Cartão SD*, quando existe um cartão inserido
  - Os cartões sem dispositivo ficam esmaecidos com a indicação "Não inserido".
- **Biblioteca:** banners com a contagem de itens e acesso direto a:
  - **Vídeos**
  - **Música**
  - **Fotos**
  - **Documentos** (APK, ZIP, DOCX, PDF e TXT)
- **Transferência LAN:** acesso à página de transferência.

### Navegador de ficheiros
- **Três modos de visualização:**
  - *Detalhado:* nome, tamanho e data.
  - *Compacto:* uma linha por ficheiro.
  - *Grade:* miniaturas de fotos e vídeos.
- **Ordenação** por nome ou por data.
- **Arquivos compactados:** ao tocar num ZIP, 7Z ou RAR, a app pergunta se quer descompactá-lo para uma pasta nova. Também está disponível no menu de ações. Não suporta arquivos protegidos por senha.
- **Compactar:** cria um ZIP a partir de um ficheiro ou pasta.
- **Ações sobre ficheiros** (tecla **Menu** ou **Y** do comando, ou pressão longa):
  - Copiar e colar
  - Recortar e colar (mover)
  - Renomear
  - Excluir (com confirmação)
  - Ver informação: tamanho, tipo, data de modificação e caminho
- **Abrir ficheiros** com outra app instalada (vídeo, áudio, imagem, PDF, etc.).
- **Voltar** sobe para a pasta anterior.

### Transferência LAN
- Ative a transferência e leia o **código QR** com o telemóvel ou o computador. Todos os dispositivos devem estar na mesma rede Wi-Fi ou cabo.
- **Receber do telemóvel:** a página web permite enviar um ou mais ficheiros para a pasta **Download** da TV, com barra de progresso.
- **Enviar da TV:**
  1. Na página de transferência, escolha **Enviar da TV**.
  2. Marque os ficheiros que quer enviar. Pode navegar entre pastas e a seleção mantém-se.
  3. Carregue em **Enviar (N)**.
  4. No telemóvel, descarregue cada ficheiro pela página do QR code.
- **Progresso:** a página mostra a barra de progresso no envio para a TV e em cada descarregamento para o telemóvel. Ficheiros acima de 1,5 GB descarregam diretamente pelo navegador, sem barra.
- **Desligar automático:** a transferência desliga-se sozinha ao fim de 10 minutos. Pode desativar esta opção.
- **Novos:** o banner mostra os ficheiros recebidos que ainda não viu, com um ponto azul. O ponto desaparece quando abre o ficheiro.
- **Segurança:** cada sessão usa um código aleatório de 10 caracteres. Só quem lê o QR code acede aos ficheiros.

---

## Requisitos

- Android TV ou TV Box com **Android 5.0 (API 21)** ou superior.
- Para ver e enviar ficheiros do armazenamento completo, a app precisa de **acesso a todos os ficheiros**. No Android 11 ou superior, a app pede esta permissão ao abrir pela primeira vez.
- Para a transferência LAN, o telemóvel ou computador deve estar na mesma rede que a TV.

---

## Instalação

1. Descarregue o ficheiro `.apk` mais recente.
2. Na TV, ative a instalação de apps de fontes desconhecidas para o gestor de ficheiros que usar para abrir o APK.
3. Abra o APK e siga as instruções de instalação.
4. Abra **Edition Explorer** a partir do ecrã inicial da TV.

---

## Comandos do comando remoto

| Tecla | Ação |
|---|---|
| Setas | Navegar |
| OK | Abrir pasta ou ficheiro |
| Voltar | Subir de pasta / voltar ao ecrã anterior |
| Menu ou Y | Abrir o menu de ações do item selecionado |

---

## Limitações conhecidas

- A **primeira leitura da biblioteca** pode demorar em armazenamentos grandes. As contagens dos banners aparecem quando termina.
- A transferência LAN usa **HTTP** dentro da rede local. Use-a apenas em redes de confiança.
- A deteção de **USB** e **cartão SD** depende da forma como o sistema monta os volumes (em `/storage` ou `/mnt/media_rw`). Os números "Drive 1", "Drive 2" seguem a ordem de deteção, não o número físico da porta.
- Ficheiros de pastas do sistema (`Android/data`, `Android/obb`) não aparecem na biblioteca.

---

## Desenvolvimento

Projeto Android em Java.

- `app/src/main/java/com/edition/explorer/`
  - `HomeActivity`: ecrã inicial
  - `BrowserActivity`: navegador de ficheiros e modo de seleção
  - `LanShareActivity`: página de transferência
  - `LanShareServer`: servidor local (receber, enviar e página web)
  - `LibraryIndex`, `StorageHelper`, `SeenStore`, `ThumbnailLoader`, `FileOps`, `SendQueue`: apoio
- Compilação: `gradle assembleDebug` (ou o workflow do GitHub Actions do repositório).
- Dependências principais: AndroidX (AppCompat, Core, RecyclerView), NanoHTTPD (servidor HTTP) e ZXing (código QR).

---

## Versão

**2.0**: ecrã inicial para TV, biblioteca, três modos de visualização, ações sobre ficheiros, transferência LAN nos dois sentidos e ponto de "novo".
