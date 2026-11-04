# Respiro

App Android para **reduzir o tempo de tela**. Em vez de proibir as redes sociais, ele coloca
atrito nos momentos certos: uma pausa antes de abrir, uma versão sem feed infinito e um
painel que mostra o que vale a pena.

Kotlin puro, **sem bibliotecas externas** (APK de ~170 KB), interface em preto e branco.

## Como funciona

O app atua em três pontos:

```
 Você toca no Instagram
          │
          ▼
 ┌─────────────────────┐   1. PAUSA (GuardService + InterceptActivity)
 │ respiração 5–30 s   │   Um serviço de acessibilidade percebe que o app abriu
 │ "O que veio fazer?" │   e mostra uma pausa antes. Cada abertura no dia deixa
 └─────────────────────┘   a próxima mais longa.
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
│   ├── GuardService.kt      serviço de acessibilidade: detecta qual app abriu
│   ├── InterceptActivity.kt tela de pausa (respiração, intenção, decisão)
│   ├── WebActivity.kt       navegador da rede: sessão, filtros, voltar
│   ├── LinkActivity.kt      recebe links (ex.: do WhatsApp) e abre na versão filtrada
│   ├── MainActivity.kt      abas Hoje, Painel e Ajustes
│   ├── ReflectionActivity.kt reflexão diária + lembrete (Reminders)
│   ├── Ui.kt                componentes visuais (botões, chips, gráficos, respiração)
│   └── Glyphs.kt            ícones das redes desenhados em código
└── assets/
    ├── filter.js            filtros injetados nas páginas (comentado passo a passo)
    └── home.html            página local "Feed oculto"
```

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

Como isso foi conseguido:
- **O feed nem carrega.** A página inicial de cada rede é uma página local de 2 KB.
- **O JavaScript dos sites fica pausado fora da tela** (`pauseTimers`). Sem isso, o Instagram gastava 15% de CPU em segundo plano.
- **O filtro só roda quando a página muda**, no máximo a cada 700 ms. Com a página parada, ele não faz nada.
- **O serviço de acessibilidade só escuta trocas de janela** e nunca lê o conteúdo da tela.

## Compilar e instalar

Requer o Android SDK (API 35) e o JDK 17.

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Na primeira abertura, a aba **Hoje** pede três permissões:

1. **Acesso ao uso**, para medir o tempo de tela.
2. **Acessibilidade**, para a pausa ao abrir os apps. Se aparecer bloqueada: Info do app → ⋮ → *Permitir configurações restritas*.
3. **Notificações**, para o lembrete da reflexão.

Opcional: em **Ajustes → Abrir links no Respiro**, os links das redes passam a abrir no app.

> Não use `adb shell am force-stop app.respiro`: o Android desliga a acessibilidade
> de um app forçado a parar.

## Limitações

- Os filtros dependem da estrutura dos sites. Se o Instagram ou o YouTube mudarem o HTML, os seletores em `Core.kt` (`Sites.builtin`) precisam de ajuste.
- O login do Google pode recusar o WebView.
- Apps instalados que são "donos" dos próprios links (como X e LinkedIn) continuam recebendo esses links.
