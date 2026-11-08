# Kairos XC

App IPTV **nativa** per **Fire TV, Android TV, Google TV e telefoni**, con la **struttura di MirkoTeam** (Home a cerchi, portali, icone in alto e in basso) ma grafica moderna con i **colori del logo** (prugna scuro, arancio → rosa) e **molto più leggera e veloce**.
Pacchetto `com.kairos.xc`: si installa **accanto** a Kairos Pro, Kairos TV e Kairos Lite e usa lo **stesso pannello Kairos** (stesso tipo di codice di attivazione `KX..-....`).

![logo](docs/logo-512.png)

## Perché è più leggera di MirkoTeam

| | MirkoTeam 7.0 | Kairos XC |
|---|---|---|
| Lettori video | 3 (VLC, FFmpeg, ExoPlayer) | 2 (ExoPlayer, VLC come riserva) |
| VPN | OpenVPN (librerie native pesanti) | WireGuard dal pannello |
| Dimensione APK | ~97 MB | circa 4-5 MB |
| Avvio | attese fisse di 2-5 secondi | nessuna attesa: logo immediato, Home già pronta |
| Liste | in memoria | su disco (SQLite), letta solo la parte visibile |

## Apertura istantanea

- **Logo all'istante**: il logo è lo sfondo della finestra, lo disegna Android nel momento in cui si preme l'icona.
- **Home leggerissima**: solo pulsanti e un'immagine di sfondo piccola (960×540), nessuna lista da caricare.
- **Portali**: le liste del portale in uso e di quello usato prima restano sul disco, quindi tornare indietro è immediato.
- **Abbonamento**: si parte con l'ultimo stato salvato, il pannello si ricontrolla in sottofondo.
- **Profilo di avvio** (Baseline Profile): Android compila in anticipo il codice usato all'apertura e nelle liste (Android 7 e successivi).
- Modalità leggera automatica sulle chiavette con poca memoria, nessuna WebView, nessuna animazione.

## Cosa c'è

- **Home come MirkoTeam**: in alto logo Kairos XC, nome del portale in uso e scadenza, **Cerca · VPN · MSG · UPDATE**; al centro **Diretta Live TV · Film On Demand · Serie TV**; in basso **Account · Favorite** e **Settings**.
- **Account = portali**: i portali sono le playlist assegnate al cliente nel pannello (chiamale per esempio `SERVER-1-OTT`, `SERVER-2-ATL`…). Da *Account* il cliente sceglie il portale; l'app ne usa uno alla volta. Se nel pannello il permesso «cambio playlist» è spento, il portale lo decide il fornitore.
- **Live TV** con categorie e guida adesso/dopo: primo OK = anteprima nel riquadro, secondo OK = schermo intero (si può spegnere nelle Impostazioni).
- **Film e Serie TV** come MirkoTeam: categorie a sinistra e locandine a griglia (con Preferiti e «Continua a guardare»), schede, stagioni ed episodi, ricerca, profili (anche bambini).
- Dal pannello: attivazione e scadenza, portali/playlist, cambio DNS, *Disconnetti*, visioni contemporanee, permessi, profili, collegamento di altre TV, messaggi (MSG), VPN, errori. In *Utenti online* la versione compare come `xc …`.
- **Controllo remoto → Comandi**: riavvia, svuota cache e riavvia, aggiorna le liste, apri le impostazioni, aggiorna l'app, speed test (Cloudflare: il risultato torna al pannello), permesso per gli aggiornamenti, reimposta PIN, chiudi. Sono invisibili al cliente: nessun avviso che citi il fornitore o il pannello (dopo «Reimposta PIN» l'app chiede subito un PIN nuovo, come al primo avvio); durante la visione liste, permesso, speed test e scelta del PIN aspettano la chiusura del lettore.
- **Aggiornamenti automatici** dalle release di questo repository.

Non ci sono, di proposito: radio, catch up, multischermo, sveglia, registrazioni, guida TV a griglia, speed test nei menu (c'è solo quello chiesto dal pannello), anteprima video nella lista canali.

## Funzioni intelligenti (dal pannello)

Le funzioni «intelligenti» della Kairos Pro, in versione leggera per le chiavette da 1 GB (Fire TV Stick, Lite). Si accendono dal pannello: **Controllo remoto › Kairos XC › «Funzioni intelligenti»: Accese | Spente**, nel profilo (tutti i clienti XC) o per il singolo cliente (vince sul profilo). **Spente di serie.**

| Funzione | Cosa vede il cliente | Cosa vede il titolare |
|---|---|---|
| **Decoder del dispositivo** (sempre attivo) | il canale parte nella versione che il dispositivo regge: 4K su TV o decoder che non lo reggono → FHD, HEVC senza decoder HEVC → H.264 (anche 10 bit e 50/60 fps), fra le versioni dello stesso canale che XC già conosce; con «Qualità video: Massima» (o una qualità scelta) resta quella | «Dati» › Auto-controlli › *Decodifica del dispositivo* |
| **Canali non funzionanti** (sempre attivo) | il canale va **in fondo** alla lista e allo zapping con «non disponibile»; torna al suo posto quando il pannello lo toglie | *Errori delle app › «Canali non funzionanti»* (stesso contratto della Pro: `ck` negli errori, anche da VLC; `dv`, `ck` e `dead` nel battito) |
| **Lettore imparato** | con «Automatico» parte da sé con VLC sui formati (TS, HLS, MP4, MKV, varianti HEVC) dove ExoPlayer su quel dispositivo fallisce e VLC va; se VLC comincia a fallire si torna a ExoPlayer. Mai contro il lettore scelto dal cliente o dal pannello; si azzera a ogni versione dell'app | «Dati» › *Lettore imparato* (es. «TS → VLC») |
| **Auto-controlli** | niente, salvo «Abbonamento da rinnovare» (linea scaduta o sospesa, al più una volta al giorno) e l'orologio sbagliato (ogni 3 giorni). Mai «fornitore» | «Salute delle app» e «Dati» › Auto-controlli: server (anche passaggio da solo al **server di riserva**), linea (scaduta, sospesa, connessioni piene, giorni alla scadenza), guida, spazio (sotto 300 MB pulisce le cache delle immagini), orologio, lettore imparato, decodifica |
| **Cambio canale** | zapping un po' più rapido sui canali con server diversi: si risolve in anticipo il DNS del canale più probabile (verso dello zapping o sequenza imparata). Niente secondo lettore | — |
| **Promemoria** | «🔔 Tra poco» 10 minuti prima dei programmi visti in almeno 2 giorni diversi e 15 minuti prima delle partite delle squadre seguite, con **«Guarda»**, «Ignora», «Non avvisarmi più»; si spengono da *Impostazioni › Promemoria automatici* | — |
| **«Per te»** | pulsante PER TE in basso nella Home: canali guardati di solito a quell'ora (feriali, sabato e domenica separati), serie da riprendere, novità delle categorie viste in quella fascia | — |
| **«Stasera»** | pulsante STASERA in basso nella Home (anche in *Cerca*): da 3 a 5 proposte con il perché (serie da riprendere, film e serie delle categorie che guarda, novità, prima serata dei suoi canali); le sceglie l'assistente, senza assistente l'app | — |
| **Assistente IA** | in *Cerca*: «✦ Cerca con l'assistente» per le frasi («film d'azione anni 90», «la partita dell'Inter stasera»), domande libere («chi è l'attore di Rambo?»), risultati apribili dalla libreria e dalla TV; messaggi semplici («Per oggi le domande all'assistente sono finite: riprendono domani») e «Chiedi altre domande» | il motore come per la Pro: scheda del cliente (Gemini di serie, Claude per cliente), profilo Kairos XC › «Assistente IA», pagina Assistente IA; stesse domande al giorno |
| **Ricerca a voce** | 🎤 accanto al campo di *Cerca*, solo se il dispositivo ha il riconoscimento vocale (Android TV e Google TV di solito sì, Fire TV no); in italiano, permesso del microfono chiesto al primo uso | — |

**Budget di leggerezza** (`Budget` in `SmartLogic.kt`, provato dai test JVM):
- **zero lavoro nuovo** nel percorso d'avvio fino alla Home: il file delle funzioni (`smart`, separato dalle impostazioni) si apre la prima volta **dopo 60 s**; i pulsanti PER TE e STASERA si caricano (ViewStub) solo se le funzioni sono accese e dopo il primo disegno della Home;
- il lavoro in sottofondo parte **solo nei menu**, mai durante la visione (né con l'anteprima nella lista canali), **uno alla volta** su un thread a **priorità minima**; nel lettore solo conti in memoria (e una risoluzione DNS dopo il cambio canale);
- **nessuna lista in memoria**: archivio SQLite con LIMIT; i canali non funzionanti in fondo con due letture nell'ordine dell'indice (niente riordino); la guida per promemoria e «Stasera» è una **guida piccola** (`smart.db`) con i soli canali che si guardano di più (8, sulle chiavette 4), una dozzina di programmi ciascuno dalla guida breve del server;
- abitudini e promemoria **per profilo**, con un tetto di **200 voci** (canali 50, categorie 25, programmi 50, squadre 30, promemoria 30, «non avvisarmi più» 15); lettore imparato 12 formati; zapping 100 canali;
- **nessuna libreria nuova**;
- sulle chiavette deboli (classe D): auto-controlli ogni **60 minuti** invece di 30, guida piccola di 4 canali (2 per giro), nessuna funzione più frequente che altrove, immagini delle pagine «Per te» e «Stasera» piccole come il resto dell'app (36 dp; con «Leggerissima» niente loghi).

**Spente** (di serie): Kairos XC fa quello che faceva prima e non esegue il lavoro nuovo in sottofondo. Restano solo le due parti che non costano nulla e correggono soltanto: i **decoder** letti una volta (dopo 60 s, nei menu) con la versione giusta del canale, e i **canali non funzionanti** in fondo alla lista (con il `ck` negli errori e l'impronta `dv` nel battito).

## Messa in funzione (una volta sola)

1. Crea su GitHub il repository **`kairos-xc`** (pubblico, altrimenti Downloader e l'aggiornamento automatico non scaricano l'APK) e carica questi file.
2. **Chiave di firma**: in *Settings → Secrets and variables → Actions* crea `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS`. Puoi riusare gli stessi valori di Kairos TV, oppure crearne di nuovi con *Actions → Prepara segreti → Run workflow*.
   > ⚠️ Se perdi la chiave di firma non potrai più aggiornare l'app sui dispositivi dei clienti.
3. Facoltativo, in *Variables*: `XC_APP_NAME` (nome sotto l'icona, predefinito «Kairos XC»), `KAIROS_SERVER_URL` (indirizzo del pannello).

## Pubblicare una versione

*Releases → Draft a new release* → tag `v1.0.0` (poi `v1.0.1`, `v1.1.0`…) → *Publish*. Dopo pochi minuti alla release sono allegati `kairos-xc.apk` e `kairos-xc.json`.

- Link fisso per **Downloader** e per lo store:
  `https://github.com/mariopiccolo8562-lang/kairos-xc/releases/latest/download/kairos-xc.apk`
- Le app installate leggono `kairos-xc.json` dell'ultima release e propongono l'aggiornamento da sole.

## Pannello

Per ora l'app si presenta al pannello come Kairos TV (stesse funzioni) e si distingue in *Utenti online* dalla versione `xc …`. Gli aggiornamenti arrivano comunque da questo repository.

## Struttura

| Cartella | Cosa contiene |
|---|---|
| `app/src/main/java/com/kairos/xc/` | `HomeActivity` (Home a cerchi, portali), `VodActivity` (Film/Serie a griglia), `LiveActivity`, `DetailActivity`, `PlayerActivity`, `Sync`/`Db` (liste su disco, per portale), `Panel` (pannello Kairos); funzioni intelligenti: `Smart` (interruttore, lavoro in sottofondo, decoder, lettore imparato, canali non funzionanti, zapping, abitudini, promemoria), `AutoCheck`, `Guide` (guida piccola), `Ai` e `SmartActivity` («Per te», «Stasera»), logica senza Android in `SmartLogic`, `Habits`, `AiFind` |
| `app/src/test/` | prove JVM (comandi del pannello, speed test, budget di leggerezza e tetti, decoder e versione, lettore imparato e scelta del lettore, canali, abitudini e promemoria, criteri di ricerca) |
| `app/src/main/res/` | Grafica: colori, layout, logo |
| `app/src/main/baseline-prof.txt` | Profilo di avvio |
| `docs/` | Logo e banner (SVG e PNG), `src/grafica.py` (sfondi e cerchi 3D) e `src/pulsanti.py` (pulsanti in rilievo) |
| `.github/workflows/` | Compilazione e pubblicazione, preparazione dei segreti |
