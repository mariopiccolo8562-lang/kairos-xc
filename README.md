# Kairos XC

App IPTV **nativa** per **Fire TV, Android TV, Google TV e telefoni**, con la **struttura di MirkoTeam** (Home a cerchi, portali, icone in alto e in basso) ma grafica moderna con i **colori del logo** (prugna scuro, arancio → rosa) e **molto più leggera e veloce**.
Pacchetto `com.kairos.xc`: si installa **accanto** a Kairos Pro, Kairos TV e Kairos Lite e usa lo **stesso pannello Kairos** (stesso tipo di codice di attivazione `KX..-....`).

![logo](docs/logo-512.png)

## Perché è più leggera di MirkoTeam

| | MirkoTeam 7.0 | Kairos XC |
|---|---|---|
| Lettori video | 3 (VLC, FFmpeg, ExoPlayer) | 1 (ExoPlayer) |
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

## Qualità dell'immagine (versioni dello stesso canale 4K / FHD / HD / SD)

Con la linea buona la qualità non si tocca, **anche sulle chiavette da 1 GB** (regole in `QualityPolicy.kt`, provate in `QualityPolicyTest`):

- **Il canale parte nella versione scelta.** La chiavetta non abbassa la qualità da sola: decide solo che il 4K non serve (le chiavette deboli non lo decodificano; sugli schermi non 4K si parte dal FHD, che sembra uguale). «Qualità video: Massima» tiene il 4K anche sugli schermi 1080p.
- **La linea abbassa la qualità solo con prove recenti:** blocchi su almeno 2 canali nell'ultima mezz'ora (solo in memoria: dopo mezz'ora senza blocchi la qualità torna piena) oppure banda misurata da meno di 2 ore sotto i 4 Mbps (SD solo sotto i 2,5 Mbps). Prima un giudizio valeva 12 ore e una banda vecchia poteva far partire tutto in SD per sempre.
- **Discesa anticipata** solo con un calo vero e costante del buffer verso lo stallo (non per un vuoto del Wi-Fi) e solo sul canale in onda. Dopo 3 blocchi in 90 s i canali dei 10 minuti successivi partono un gradino sotto.
- **Risalita**: dopo 6 minuti senza blocchi si riprova la versione piena per il dispositivo.
- **Chiavette che non reggono il FHD**: se l'immagine va a scatti (con la decodifica hardware) su 2 canali diversi in 7 giorni, i canali partono in HD per 7 giorni, poi si riprova.
- **«Massima»**: la linea non abbassa mai la qualità. **«1080p / 720p / 480p»**: tetto anche sulle versioni del canale. **«Qualità adattiva» spenta dal pannello**: nessun cambio automatico.
- Il dispositivo debole tiene solo i buffer più piccoli (memoria); i flussi HLS a più qualità seguono la linea, non la chiavetta.
- Anteprima nella lista canali: i canali FHD e 4K in HD se la playlist ce l'ha (prima l'SD).

Non ci sono, di proposito: radio, catch up, multischermo, sveglia, registrazioni, guida TV a griglia, VLC, speed test nei menu (c'è solo quello chiesto dal pannello), anteprima video nella lista canali.

## Adulti fuori dalla Home

Voce `adult` del controllo remoto (spunta del pannello «Nascondi gli adulti dalla Home»), uguale in tutte le app Kairos:
- Diversa da `on`, anche assente o vuota: la Home a cerchi non mostra titoli né canali, quindi lì non c'è niente da togliere.
- Negli elenchi (Film, Serie TV, Live TV) le categorie per adulti vanno in fondo, come quelle nascoste, e restano raggiungibili. Ricerca, guida TV e zapping non cambiano; il PIN del controllo genitori resta com'è.
- `on`: tutto come prima, vale solo la lista «Categorie nascoste dalla Home» (`home.hide`). Quella lista vale sempre, in aggiunta al riconoscimento automatico.

Riconoscimento in `AdultFilter.kt` (normalizzazione NFKD e le espressioni del contratto comune: «Adult Swim» resta; in XC serve solo quella delle categorie, perché la Home non ha titoli), regole in `HomeCfg.kt`. Prove: `AdultFilterTest` (i 94 esempi di `src/test/resources/adult-vectors.json`) e `HomeCfgTest`. Ogni categoria si riconosce una volta sola (cache per nome) e solo quando si apre un elenco: nessun peso in più per le chiavette da 1 GB; niente lavoro in più durante la visione.

## Riquadro della qualità nel lettore

Voce `qbox` del controllo remoto, uguale in tutte le app Kairos, per il riquadro in alto a destra dei canali in diretta («FHD · 1080p · 50 fps»: ~6 s all'apertura del canale, ~4 s a ogni cambio di risoluzione e sempre con la barra informazioni):
- `off`: il riquadro non compare mai, come con il permesso «resInd» del cliente bloccato.
- `noq`: il riquadro resta senza la sigla di qualità: «1080p · 50 fps».
- `noq` e `off`: anche l'etichetta in cima alla barra informazioni perde la sigla («LIVE» invece di «FHD · LIVE»; su film e serie sparisce).
- `full`, vuota, assente o qualsiasi altro valore: come prima.

Il permesso «resInd» bloccato lo nasconde sempre. Non cambiano i nomi dei canali, gli avvisi, la qualità adattiva, e i dati mandati al pannello (risoluzione e FPS in «Online ora»). Una voce cambiata durante la visione vale dal prossimo aggiornamento del riquadro. Il lettore VLC non ha questo riquadro (come prima). Regole in `QBox.kt`, prove in `QBoxTest`; nessun peso in più per le chiavette.

## Messa in funzione (una volta sola)

1. Crea su GitHub il repository **`kairos-xc`** (pubblico, altrimenti Downloader e l'aggiornamento automatico non scaricano l'APK) e carica questi file.
2. **Chiave di firma**: in *Settings → Secrets and variables → Actions* crea `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS`. Puoi riusare gli stessi valori di Kairos TV, oppure crearne di nuovi con *Actions → Prepara segreti → Run workflow*.
   > ⚠️ Se perdi la chiave di firma non potrai più aggiornare l'app sui dispositivi dei clienti.
3. Facoltativo, in *Variables*: `XC_APP_NAME` (nome sotto l'icona, predefinito «Kairos XC»), `KAIROS_SERVER_URL` (indirizzo del pannello), `KAIROS_DL_BASE` (indirizzo dei download e degli aggiornamenti; predefinito `https://store-k.download`).

## Pubblicare una versione

**Da sola:** cambia il numero in `versione.txt` su `main` (per esempio da `3.4.4` a `3.4.5`). La compilazione di quella modifica crea il tag `v3.4.5` e pubblica la release, senza passare da GitHub. La regola sta in `.github/scripts/versione.sh` (provata a ogni compilazione da `versione_test.sh`): pubblica solo da `main` e solo se la versione è nuova e più alta dell'ultima pubblicata; con un numero non valido, più basso o già pubblicato esce solo l'APK di prova. Le altre modifiche a `main` non pubblicano nulla.

**A mano, come prima:** *Releases → Draft a new release* → tag `v1.0.0` (poi `v1.0.1`, `v1.1.0`…) → *Publish*. Dopo pochi minuti alla release sono allegati `kairos-xc.apk` e `kairos-xc.json`.

- Link fisso per **Downloader** e per lo store:
  `https://github.com/mariopiccolo8562-lang/kairos-xc/releases/latest/download/kairos-xc.apk`
- Le app installate chiedono gli aggiornamenti a `https://store-k.download/get/xc.json` (indirizzo dei download `DL_BASE` in `app/build.gradle.kts`, variabile `KAIROS_DL_BASE`), poi al pannello `https://panel-kairos.com/get/xc.json`; scaricano l'APK dal link che c'è dentro (se non riesce, dalle basi successive) e propongono l'aggiornamento da sole. Solo se nessuno dei due risponde leggono `kairos-xc.json` dell'ultima release (riserva finché il repository è pubblico).

## Pannello

Per ora l'app si presenta al pannello come Kairos TV (stesse funzioni) e si distingue in *Utenti online* dalla versione `xc …`. Gli aggiornamenti arrivano comunque da questo repository.

## Struttura

| Cartella | Cosa contiene |
|---|---|
| `app/src/main/java/com/kairos/xc/` | `HomeActivity` (Home a cerchi, portali), `VodActivity` (Film/Serie a griglia), `LiveActivity`, `DetailActivity`, `PlayerActivity`, `Sync`/`Db` (liste su disco, per portale), `Panel` (pannello Kairos) |
| `app/src/main/res/` | Grafica: colori, layout, logo |
| `app/src/main/baseline-prof.txt` | Profilo di avvio |
| `docs/` | Logo e banner (SVG e PNG), `src/grafica.py` (sfondi e cerchi 3D) e `src/pulsanti.py` (pulsanti in rilievo) |
| `.github/workflows/` | Compilazione e pubblicazione, preparazione dei segreti |
