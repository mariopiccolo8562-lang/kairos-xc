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
- **Aggiornamenti automatici** dalle release di questo repository.

Non ci sono, di proposito: radio, catch up, multischermo, sveglia, registrazioni, guida TV a griglia, VLC, speed test, anteprima video nella lista canali.

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
| `app/src/main/java/com/kairos/xc/` | `HomeActivity` (Home a cerchi, portali), `VodActivity` (Film/Serie a griglia), `LiveActivity`, `DetailActivity`, `PlayerActivity`, `Sync`/`Db` (liste su disco, per portale), `Panel` (pannello Kairos) |
| `app/src/main/res/` | Grafica: colori, layout, logo |
| `app/src/main/baseline-prof.txt` | Profilo di avvio |
| `docs/` | Logo e banner (SVG e PNG), `src/grafica.py` (sfondi e cerchi 3D) e `src/pulsanti.py` (pulsanti in rilievo) |
| `.github/workflows/` | Compilazione e pubblicazione, preparazione dei segreti |
