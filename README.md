# Respiro

App Android para **reduzir o tempo de tela**. Em vez de proibir as redes sociais, ele coloca
atrito nos momentos certos: uma pausa antes de abrir, uma versão sem feed infinito e um
painel que mostra o que vale a pena.

Kotlin puro, **sem bibliotecas externas** (APK de ~190 KB), interface em preto e branco.
Tem também uma **extensão para Chrome e Firefox** no computador, que faz o mesmo nos sites e
pode ficar com os mesmos ajustes do celular.

## Como funciona

O app atua em três pontos:

```
 Você toca no Instagram (ou abre instagram.com no Chrome)
          │
          ▼
 ┌─────────────────────┐   1. PAUSA (GuardService + InterceptActivity)
 │ respiração 5–30 s   │   Um serviço de acessibilidade percebe que o app abriu
 │ "O que veio fazer?" │   e mostra uma pausa antes. Cada abertura no dia deixa
 └─────────────────────┘   a próxima mais longa. Nos navegadores, ele lê só a barra
                           de endereço; ali a única saída é a versão filtrada.
   │        │        │
 Não abrir  │   Abrir o app por 5/10/15 min
            ▼
 ┌─────────────────────┐   2. VERSÃO WEB FILTRADA (WebActivity + filter.js)
 │ "Por quanto tempo?" │   O site abre dentro do Respiro: sem feed, sem Reels/Shorts,
 │ feed oculto, busca  │   com um cronômetro e aviso depois de N telas roladas.
 └─────────────────────┘
          │ ao sair
          ▼
 ┌─────────────────────┐   3. MEDIÇÃO (MainActivity → Painel, ReflectionActivity)
 │ "Como você sai?"    │   Humor ao sair, reflexão à noite e gráficos de uso.
 └─────────────────────┘
```

## Técnicas usadas (e por quê)

| Técnica | Onde | Base |
|---|---|---|
| Pausa antes de abrir, com opção de desistir | `InterceptActivity` | Estudo do app *one sec*: a pausa fez as pessoas desistirem de 36% das aberturas, e as tentativas caíram 37% em 6 semanas (Grüning et al., *PNAS*, 2023) |
| Atrito crescente (5 s + 2 s por abertura no dia) | `InterceptActivity.setup` | Mesmo estudo: o atraso reduz o consumo |
| Pergunta de intenção | pausa e início da sessão web | Separa o uso com propósito do uso por impulso |
| Feed removido, só busca e atalhos | `assets/home.html` | Remover o feed reduziu a navegação passiva no Facebook (Lyngs et al., CHI 2020) |
| Sessão com tempo e aviso de rolagem | `WebActivity` | "Sinais de parada": o feed infinito não tem fim natural |
| Tons de cinza | `WebActivity.setupWeb` | Associado a menos tempo de tela (Holte et al., 2020, 2021) |
| Frase para passar do limite | `Ui.confirmPhrase` | Transforma continuar numa escolha consciente |
| Plano se–então | `ReflectionActivity` | *Implementation intentions* (evidência mista) |
| "Como você sai daqui?" | fim da sessão | Mede se o uso valeu a pena |

## Estrutura do código

```
app/src/main/
├── java/app/respiro/
│   ├── Core.kt              dados: preferências, sites, banco SQLite, cálculo de uso
│   ├── GuardService.kt      serviço de acessibilidade: detecta qual app abriu e, nos navegadores, o site
│   ├── InterceptActivity.kt tela de pausa (respiração, intenção, decisão)
│   ├── WebActivity.kt       navegador da rede: sessão, filtros, voltar
│   ├── LinkActivity.kt      recebe links (ex.: do WhatsApp) e abre na versão filtrada
│   ├── MainActivity.kt      abas Hoje, Painel e Ajustes
│   ├── ReflectionActivity.kt reflexão diária + lembrete (Reminders)
│   ├── Sync.kt              sincronização com a extensão pelo ntfy (cifrada)
│   ├── Ui.kt                componentes visuais (botões, chips, gráficos, respiração)
│   └── Glyphs.kt            ícones das redes desenhados em código
└── assets/
    ├── filter.js            filtros injetados nas páginas (comentado passo a passo)
    ├── home.html            página local "Feed oculto"
    └── sites.json           redes, domínios e seletores (fonte única: app e extensão)

extension/                   extensão Manifest V3 (Chrome e Firefox)
├── background.js            pausa ao navegar, tempo de uso, sincronização periódica
├── pause.html/js            tela de pausa (mesma lógica do InterceptActivity)
├── content.js               "Feed oculto", aviso de rolagem, tons de cinza, fim do passe
├── popup.html/js            tempo de hoje e modo foco
├── options.html/js          ajustes e conexão com o app
├── sync.js                  mesmo protocolo do Sync.kt
└── common.js                ajustes, sites e utilidades
```

O `filter.js` e o `sites.json` são do app. O `scripts/build-extension.sh` copia os dois para a
extensão (o `filter.js` vai embrulhado para receber a configuração do `content.js`).

### Os dados

Tudo fica no celular, num banco SQLite (`Db` em `Core.kt`):

| Tabela | Uma linha por… |
|---|---|
| `opens` | tentativa de abrir um app (resultado: desistiu, web, app…) |
| `sessions` | sessão web ou passe no app (duração, humor ao sair) |
| `reflections` | reflexão diária (nota, o que puxou, plano) |
| `usage_cache` | dia passado de uso, para não recalcular |

O tempo de tela vem do `UsageStatsManager` do Android (`Usage.compute`): uma única passada
pelos eventos do dia, somando o intervalo entre *abriu* e *fechou* de cada app (O(n) no
número de eventos). Os dias passados ficam em cache e só o dia de hoje é recalculado.

## Desempenho

Medido num Galaxy A05s (Android 15):

| Situação | CPU | Memória |
|---|---|---|
| Só o serviço de pausa, em segundo plano | ~0% | 23 MB |
| Tela Hoje aberta | 0,1% | 43 MB |
| Rede aberta no "Feed oculto" | 0,3% | 50 MB (renderizador) |
| Rede em segundo plano | ~0,5% | liberada após 3 min |
| Rolando uma página no Chrome (serviço lendo a barra de endereço) | ~0,4% | — |

Como isso foi conseguido:
- **O feed nem carrega.** A página inicial de cada rede é uma página local de 2 KB.
- **O JavaScript dos sites fica pausado fora da tela** (`pauseTimers`). Sem isso, o Instagram gastava 15% de CPU em segundo plano.
- **O filtro só roda quando a página muda**, no máximo a cada 700 ms. Com a página parada, ele não faz nada.
- **O serviço de acessibilidade só escuta trocas de janela.** Nos navegadores, também escuta mudanças
  de conteúdo, mas lê só o campo da barra de endereço, no máximo a cada 800 ms. O resto da tela nunca é lido.

## Compilar e instalar

Requer o Android SDK (API 35) e o JDK 17.

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Sem o `keystore.properties`, o build assina com a chave de debug (serve para testar, não para distribuir).

Na primeira abertura, a aba **Hoje** pede três permissões:

1. **Acesso ao uso**, para medir o tempo de tela.
2. **Acessibilidade**, para a pausa ao abrir os apps. Se aparecer bloqueada: Info do app → ⋮ → *Permitir configurações restritas*.
3. **Notificações**, para o lembrete da reflexão.

Opcional: em **Ajustes → Abrir links no Respiro**, os links das redes passam a abrir no app.

**Navegadores reconhecidos:** Chrome (testado), Samsung Internet, Firefox, Edge, Brave e Opera
(`GuardService.BROWSERS`). Se uma rede é aberta num deles, o Respiro volta a aba e mostra a pausa.
A saída é abrir a rede na versão filtrada, dentro do app, ou desistir. Se a aba foi aberta por um
link de outro app, o "voltar" fecha o navegador mas a aba continua na rede, e a pausa aparece de
novo quando o navegador volta para a frente.

> Não use `adb shell am force-stop app.respiro`: o Android desliga a acessibilidade
> de um app forçado a parar.

## Extensão do navegador

```sh
./scripts/build-extension.sh   # copia filter.js e sites.json e gera build/respiro-extension-<versão>.zip
```

- **Chrome/Edge/Brave:** `chrome://extensions` → Modo do desenvolvedor → *Carregar sem compactação* → pasta `extension/`.
- **Firefox:** `about:debugging` → Este Firefox → *Carregar extensão temporária* → `extension/manifest.json`.
  (Para instalar de vez, o Firefox exige a extensão assinada pela Mozilla.)

Cada Release traz o `.zip` da extensão junto com o APK.

O que faz: pausa com respiração antes de abrir uma rede (mesmo atraso crescente, limite com frase,
modo foco, horário de descanso), "Feed oculto" no lugar do feed, sem Reels/Shorts, aviso de rolagem,
tons de cinza e aviso no fim do tempo. O popup mostra o tempo de hoje e liga o modo foco.

## Sincronização app ↔ extensão (sem servidor próprio)

Os ajustes e a lista de sites ficam iguais nos dois lados, usando um tópico do [ntfy](https://ntfy.sh)
como canal. O histórico de uso não é sincronizado.

1. No app: **Ajustes → Extensão do navegador → Conectar extensão → Gerar código** e mande o código para
   o computador (WhatsApp para você mesmo, e-mail…). Ou gere o código na extensão e cole no app.
2. Na extensão: **Ajustes → App no celular** → cole o código → *Conectar*.

Como funciona (`Sync.kt` e `extension/sync.js`):

- O código `RESPIRO1:<tópico>:<chave>` tem um tópico aleatório de 128 bits e uma chave AES-256.
- **Tudo vai cifrado** (AES-GCM). O ntfy.sh, ou quem souber o tópico, só vê texto ilegível.
- Ao mudar um ajuste, o estado completo é publicado 3 s depois. Vence o mais recente.
- O app busca mensagens ao ser aberto. A extensão busca a cada 5 min e ao abrir o popup. Não há
  conexão permanente.
- O ntfy.sh guarda as mensagens por ~12 h. Ao conectar, cada lado pede o estado do outro.
- Dá para usar um servidor ntfy próprio no campo de servidor da extensão.

## Versões e releases

Publicar uma versão é **só criar uma tag**. O GitHub Actions (`.github/workflows/android.yml`)
compila, assina e cria a Release com o APK:

```sh
git tag v1.1.0
git push origin v1.1.0
```

| Evento | O que acontece |
|---|---|
| push ou PR na `main` | compila para conferir que nada quebrou (não publica nada) |
| push de tag `v*` | compila, assina com a chave de release, monta a extensão e publica a Release (APK + `.zip`) com notas geradas dos commits |

A versão vem da tag: `v1.2.3` vira `versionName 1.2.3` e `versionCode 10203`. Use
[versionamento semântico](https://semver.org/lang/pt-BR/): *patch* para correções, *minor* para
funcionalidades e *major* para mudanças grandes.

**Compatibilidade:** Android 11 ou mais novo (`minSdk 30`), qualquer processador (o app não tem
código nativo). Precisa do *Android System WebView*, que vem atualizado em praticamente todos os
celulares.

**Atualizações automáticas para quem usa:** instale o [Obtainium](https://github.com/ImranR98/Obtainium)
e adicione o link do repositório. Isso exige que o repositório, ou um repositório só de releases, seja público.

### Chave de assinatura

Todas as versões precisam ser assinadas com **a mesma chave**. Sem ela, quem já instalou não
consegue atualizar. A chave fica fora do git:

- `~/.android/respiro-release.jks`: a chave. **Faça backup.**
- `keystore.properties`: o caminho e as senhas (ignorado pelo git).
- No GitHub: os secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`,
  enviados com `scripts/enviar-chave.sh`.

## Limitações

- Os filtros dependem da estrutura dos sites. Se o Instagram ou o YouTube mudarem o HTML, os seletores em `Core.kt` (`Sites.builtin`) precisam de ajuste.
- O login do Google pode recusar o WebView.
- Apps instalados que são "donos" dos próprios links (como X e LinkedIn) continuam recebendo esses links.

## Padrão de commits

[Conventional Commits](https://www.conventionalcommits.org/pt-br/), com a descrição em português e no imperativo:

```
tipo(escopo): descrição curta

Corpo opcional explicando o porquê.
```

| Tipo | Uso |
|---|---|
| `feat` | funcionalidade nova |
| `fix` | correção de bug |
| `perf` | melhoria de desempenho |
| `refactor` | mudança de código sem mudar comportamento |
| `docs` | documentação |
| `chore` | build, dependências, configuração |

Escopos usados: `core`, `web`, `pausa`, `painel`, `ui`, `reflexao`, `sync`, `extensao`.
