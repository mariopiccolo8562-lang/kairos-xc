/* =====================================================================
   PANNELLO KAIROS STORE — Cloudflare Worker (separato dal pannello Kairos)

   Gestisce le app dello store (apps.json e icone su GitHub) con:
   - primo avvio protetto da SETUP_KEY
   - accesso con username + password (PBKDF2) e verifica in due passaggi
     obbligatoria (Google Authenticator / Authy) + 10 codici di recupero
   - limite di 8 tentativi sbagliati per IP (blocco 15 minuti) e limite globale
   - Turnstile (facoltativo), avvisi Telegram (facoltativi), paesi consentiti
   - sessioni sicure: cookie HttpOnly/Secure/SameSite=Strict, uscita dopo
     30 minuti di inattività (o 30 giorni con "Ricorda questo dispositivo")
   - "Esci da tutti i dispositivi", blocco di emergenza, registro accessi
   - il token GitHub resta un Secret del Worker: il browser non lo vede mai

   Collegamenti richiesti (Settings → Bindings / Variables and Secrets):
     DB              KV namespace
     GITHUB_TOKEN    Secret — token fine-grained: solo kairos-store, Contents Read and write
     SETUP_KEY       Secret — chiave per il primo avvio e per lo sblocco di emergenza
   Facoltativi:
     GITHUB_REPO        Text  (predefinito mariopiccolo8562-lang/kairos-store)
     STORE_URL          Text  (predefinito https://mariopiccolo8562-lang.github.io/kairos-store/)
     ALLOWED_COUNTRIES  Text  es. IT oppure IT,SM,CH
     TURNSTILE_SITEKEY  Secret + TURNSTILE_SECRET Secret
     TELEGRAM_TOKEN     Secret + TELEGRAM_CHAT Secret
   ===================================================================== */

const VERSION = "1.0";
const MAX_FAIL_IP = 8;           // tentativi sbagliati per IP
const FAIL_WINDOW = 15 * 60;     // secondi di blocco
const MAX_FAIL_ALL = 50;         // tentativi sbagliati totali in un'ora (attacchi da molti IP)
const IDLE_MIN = 30;             // uscita per inattività (senza "Ricorda")
const REMEMBER_DAYS = 30;        // durata con "Ricorda questo dispositivo"
const COOKIE = "__Host-kps";
const PAGE = "<!DOCTYPE html>\n<html lang=\"it\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<meta name=\"robots\" content=\"noindex, nofollow\">\n<meta name=\"referrer\" content=\"no-referrer\">\n<title>Pannello · Kairos Store</title>\n<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n<link href=\"https://fonts.googleapis.com/css2?family=Orbitron:wght@600;800&family=Rajdhani:wght@500;600;700&display=swap\" rel=\"stylesheet\">\n<style>\n:root{--bg:#05060f;--panel:#0d1124;--panel2:#141a33;--edge:rgba(140,170,255,.18);--ink:#e8f1ff;--muted:#8b97b5;\n  --c1:#22d3ee;--c2:#8b5cf6;--c3:#ec4899;--ok:#34d399;--bad:#fb7185;--grad:linear-gradient(110deg,var(--c1),var(--c2) 55%,var(--c3));\n  --display:\"Orbitron\",system-ui,sans-serif;--body:\"Rajdhani\",system-ui,-apple-system,\"Segoe UI\",Roboto,sans-serif}\n*{box-sizing:border-box}\nhtml,body{margin:0;background:var(--bg);color:var(--ink);font:500 17px/1.45 var(--body)}\nbody{background:radial-gradient(900px 500px at 10% -10%,rgba(14,116,144,.3),transparent 70%),radial-gradient(800px 500px at 100% 10%,rgba(91,33,182,.28),transparent 70%),var(--bg);min-height:100vh}\na{color:var(--c1)}\nbutton,input,textarea,select{font:inherit;color:inherit}\n.wrap{max-width:1000px;margin:0 auto;padding:24px 18px 120px}\nheader{display:flex;align-items:center;gap:14px;margin-bottom:24px}\nheader img{width:52px;height:52px}\nheader h1{margin:0;font:800 22px var(--display);letter-spacing:.12em;background:var(--grad);-webkit-background-clip:text;background-clip:text;color:transparent}\nheader p{margin:2px 0 0;color:var(--muted);font-size:15px}\nheader .sp{flex:1}\n.card{background:rgba(13,17,36,.88);border:1px solid var(--edge);border-radius:18px;padding:22px;margin-bottom:18px}\nh2{margin:0 0 14px;font:700 15px var(--display);letter-spacing:.16em;color:var(--c1)}\nlabel{display:block;font-size:14px;color:var(--muted);margin:12px 0 5px;font-weight:600;letter-spacing:.02em}\nlabel small{font-weight:500;opacity:.85}\ninput[type=text],input[type=password],input[type=url],textarea{width:100%;padding:11px 13px;border-radius:11px;border:1px solid var(--edge);\n  background:#070a18;outline:none;font-size:16px}\ntextarea{min-height:76px;resize:vertical}\ninput:focus,textarea:focus{border-color:var(--c1);box-shadow:0 0 0 3px rgba(34,211,238,.2)}\ninput.err,textarea.err{border-color:var(--bad);box-shadow:0 0 0 3px rgba(251,113,133,.2)}\n.row{display:grid;grid-template-columns:1fr 1fr;gap:0 16px}\n.row3{display:grid;grid-template-columns:2fr 1fr 1fr;gap:0 16px}\n@media (max-width:640px){.row,.row3{grid-template-columns:1fr}}\n.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;border:0;border-radius:12px;padding:12px 18px;cursor:pointer;\n  font:700 13px var(--display);letter-spacing:.14em;background:var(--grad);color:#031018;text-decoration:none}\n.btn:disabled{opacity:.45;cursor:not-allowed}\n.btn.ghost{background:transparent;border:1px solid var(--edge);color:var(--ink)}\n.btn.ghost:hover{border-color:var(--c1)}\n.btn.danger{background:transparent;border:1px solid rgba(251,113,133,.5);color:#fda4af}\n.btn.danger:hover{background:rgba(251,113,133,.12)}\n.btn.small{padding:8px 12px;font-size:11px;border-radius:10px}\n.check{display:flex;align-items:center;gap:10px;margin-top:12px;font-size:15px;color:var(--ink);cursor:pointer}\n.check input{width:18px;height:18px;accent-color:var(--c1)}\n.help{font-size:15px;color:#b8c3dc}\n.help ol{padding-left:20px;margin:8px 0}\n.help li{margin:4px 0}\n.msg{margin-top:12px;font-size:15px;min-height:1.3em}\n.msg.bad{color:var(--bad)} .msg.ok{color:var(--ok)}\n\n/* elenco app */\n.apps{display:flex;flex-direction:column;gap:12px}\n.app{border:1px solid var(--edge);border-radius:16px;background:var(--panel);overflow:hidden}\n.app.hiddenapp{opacity:.62}\n.head{display:flex;align-items:center;gap:12px;padding:12px 14px;cursor:pointer}\n.head .ic{width:46px;height:46px;border-radius:12px;flex:none;background:#0a0e1d;overflow:hidden;display:grid;place-items:center;\n  font:800 18px var(--display);box-shadow:0 0 0 1px rgba(255,255,255,.08),0 4px 16px -4px var(--g,#22d3ee)}\n.head .ic img{width:100%;height:100%;object-fit:cover}\n.head .tt{flex:1;min-width:0}\n.head .nm{font:700 16px var(--display);letter-spacing:.04em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n.head .sub{font-size:13px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n.badge{font:700 10px var(--display);letter-spacing:.12em;padding:3px 8px;border-radius:6px;background:#334155;color:#cbd5e1;margin-left:6px;vertical-align:2px}\n.badge.warn{background:#7c2d12;color:#fed7aa}\n.mv{display:flex;gap:6px}\n.mv button{width:34px;height:34px;border-radius:9px;border:1px solid var(--edge);background:transparent;cursor:pointer;color:var(--ink)}\n.mv button:hover{border-color:var(--c1)}\n.mv button:disabled{opacity:.3;cursor:default}\n.chev{transition:transform .2s;color:var(--muted)}\n.app.open .chev{transform:rotate(180deg)}\n.body{display:none;padding:2px 16px 16px;border-top:1px solid var(--edge)}\n.app.open .body{display:block}\n.iconrow{display:flex;gap:14px;align-items:center;margin-top:12px;flex-wrap:wrap}\n.iconrow .big{width:84px;height:84px;border-radius:18px;background:#0a0e1d;overflow:hidden;display:grid;place-items:center;font:800 30px var(--display);\n  box-shadow:0 0 0 1px rgba(255,255,255,.1),0 8px 24px -6px var(--g,#22d3ee)}\n.iconrow .big img{width:100%;height:100%;object-fit:cover}\n.iconrow .big.contain img{object-fit:contain;padding:6px}\n.iconrow select{padding:10px;border-radius:10px;border:1px solid var(--edge);background:#070a18}\n.color{display:flex;gap:10px;align-items:center}\n.color input[type=color]{width:46px;height:42px;border:1px solid var(--edge);border-radius:10px;background:transparent;padding:2px;cursor:pointer}\n.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:16px}\n\n/* barra salvataggio */\n.savebar{position:fixed;left:0;right:0;bottom:0;z-index:20;display:flex;align-items:center;gap:14px;padding:14px 18px;\n  background:rgba(5,6,15,.95);border-top:1px solid var(--edge);backdrop-filter:blur(8px)}\n.savebar .st{flex:1;font-size:15px;color:var(--muted)}\n.savebar .st.dirty{color:#fcd34d}\n.savebar .st.ok{color:var(--ok)} .savebar .st.bad{color:var(--bad)}\n.spin{display:inline-block;width:14px;height:14px;border:2px solid currentColor;border-right-color:transparent;border-radius:50%;animation:sp .8s linear infinite;vertical-align:-2px}\n@keyframes sp{to{transform:rotate(360deg)}}\n\n/* finestra di conferma */\n.modal{position:fixed;inset:0;z-index:50;display:none;place-items:center;background:rgba(0,0,0,.6);padding:18px}\n.modal.on{display:grid}\n.modal .box{max-width:420px;width:100%;background:var(--panel2);border:1px solid var(--edge);border-radius:18px;padding:22px}\n.modal h3{margin:0 0 8px;font:700 16px var(--display);letter-spacing:.08em}\n.modal p{margin:0 0 18px;color:#b8c3dc}\n.modal .actions{justify-content:flex-end;margin:0}\n.hidden{display:none!important}\n.logo{width:52px;height:52px;border-radius:14px;display:grid;place-items:center;font:800 26px var(--display);color:#031018;background:var(--grad);box-shadow:0 0 24px -4px rgba(139,92,246,.7)}\n.narrow{max-width:460px;margin:0 auto}\n.tabs{display:flex;gap:8px;margin-bottom:18px}\n.tabs button{flex:1;padding:12px;border-radius:12px;border:1px solid var(--edge);background:transparent;cursor:pointer;font:700 12px var(--display);letter-spacing:.14em;color:var(--muted)}\n.tabs button.on{border-color:var(--c1);color:var(--ink);background:rgba(34,211,238,.08)}\n.secret{font:700 18px/1.5 ui-monospace,Consolas,monospace;letter-spacing:.12em;background:#070a18;border:1px dashed var(--c1);border-radius:12px;padding:12px 14px;word-break:break-all;user-select:all}\n.codes{display:grid;grid-template-columns:repeat(2,1fr);gap:8px;margin:12px 0}\n.codes span{font:700 16px ui-monospace,Consolas,monospace;letter-spacing:.1em;background:#070a18;border:1px solid var(--edge);border-radius:10px;padding:8px;text-align:center;user-select:all}\n.code-in{font:700 26px ui-monospace,Consolas,monospace!important;letter-spacing:.4em;text-align:center}\n.log{width:100%;border-collapse:collapse;font-size:14px}\n.log td{padding:7px 6px;border-top:1px solid var(--edge);vertical-align:top}\n.log td:first-child{white-space:nowrap;color:var(--muted)}\n.log .no{color:var(--bad)}\n.info{display:grid;grid-template-columns:1fr 1fr;gap:10px}\n@media (max-width:640px){.info{grid-template-columns:1fr}}\n.info div{background:#070a18;border:1px solid var(--edge);border-radius:12px;padding:10px 12px;font-size:15px}\n.info b{display:block;font:700 11px var(--display);letter-spacing:.12em;color:var(--muted);margin-bottom:3px}\n.ts{margin-top:12px;min-height:0}\n.link{background:none;border:0;color:var(--c1);cursor:pointer;padding:0;font-size:15px;text-decoration:underline}\n.danger-zone{border-color:rgba(251,113,133,.35)}\n.logo{flex:none}\n@media (max-width:640px){\n  header{flex-wrap:wrap;gap:10px}\n  header h1{font-size:16px}\n  header p{font-size:13px}\n  header .sp{flex-basis:100%;height:0}\n  .logo{width:42px;height:42px;font-size:21px}\n  .savebar{flex-wrap:wrap;gap:8px;padding:10px 12px}\n  .savebar .st{flex-basis:100%;font-size:13px;line-height:1.3}\n  .savebar .btn{flex:1}\n  .wrap{padding-bottom:150px}\n}\n</style>\n</head>\n<body>\n<div class=\"wrap\">\n  <header>\n    <div class=\"logo\">K</div>\n    <div><h1>PANNELLO KAIROS STORE</h1><p>Gestisci le app del tuo store</p></div>\n    <span class=\"sp\"></span>\n    <a class=\"btn ghost small\" id=\"storeLink\" href=\"#\" target=\"_blank\" rel=\"noopener noreferrer\">APRI LO STORE</a>\n    <button class=\"btn ghost small hidden\" id=\"logoutBtn\" type=\"button\">ESCI</button>\n  </header>\n\n  <div id=\"loading\" class=\"narrow card\"><span class=\"spin\"></span> Caricamento…</div>\n\n  <!-- PRIMO AVVIO -->\n  <section class=\"card narrow hidden\" id=\"setupCard\">\n    <h2>CONFIGURAZIONE INIZIALE</h2>\n    <p class=\"help\">Succede una volta sola. Inserisci la <b>SETUP_KEY</b> che hai messo nelle impostazioni del Worker e scegli username e password.</p>\n    <label for=\"suKey\">SETUP_KEY</label><input type=\"password\" id=\"suKey\" autocomplete=\"off\">\n    <label for=\"suUser\">Username</label><input type=\"text\" id=\"suUser\" autocomplete=\"username\" spellcheck=\"false\" maxlength=\"32\">\n    <label for=\"suPass\">Password <small>(almeno 10 caratteri)</small></label><input type=\"password\" id=\"suPass\" autocomplete=\"new-password\">\n    <label for=\"suPass2\">Ripeti la password</label><input type=\"password\" id=\"suPass2\" autocomplete=\"new-password\">\n    <div class=\"ts\" id=\"tsSetup\"></div>\n    <div class=\"actions\"><button class=\"btn\" id=\"suBtn\" type=\"button\">CREA ACCESSO</button></div>\n    <div class=\"msg\" id=\"suMsg\"></div>\n  </section>\n\n  <!-- ACCESSO -->\n  <section class=\"card narrow hidden\" id=\"loginCard\">\n    <h2>ACCESSO</h2>\n    <label for=\"liUser\">Username</label><input type=\"text\" id=\"liUser\" autocomplete=\"username\" spellcheck=\"false\" maxlength=\"32\">\n    <label for=\"liPass\">Password</label><input type=\"password\" id=\"liPass\" autocomplete=\"current-password\">\n    <div id=\"liKeyBox\" class=\"hidden\"><label for=\"liKey\">SETUP_KEY <small>(solo finché non attivi la verifica in due passaggi)</small></label><input type=\"password\" id=\"liKey\" autocomplete=\"off\"></div>\n    <label class=\"check\"><input type=\"checkbox\" id=\"liRemember\" checked> Ricorda questo dispositivo per 30 giorni</label>\n    <div class=\"ts\" id=\"tsLogin\"></div>\n    <div class=\"actions\"><button class=\"btn\" id=\"liBtn\" type=\"button\">ENTRA</button></div>\n    <div class=\"msg\" id=\"liMsg\"></div>\n  </section>\n\n  <!-- CODICE 2FA -->\n  <section class=\"card narrow hidden\" id=\"twoCard\">\n    <h2>VERIFICA IN DUE PASSAGGI</h2>\n    <p class=\"help\" id=\"twoHelp\">Apri Google Authenticator (o Authy) e scrivi il codice di 6 cifre di <b>Kairos Store</b>.</p>\n    <input type=\"text\" id=\"twoCode\" class=\"code-in\" inputmode=\"numeric\" autocomplete=\"one-time-code\" maxlength=\"9\" placeholder=\"••••••\">\n    <div class=\"actions\" style=\"align-items:center\"><button class=\"btn\" id=\"twoBtn\" type=\"button\">CONFERMA</button>\n      <button class=\"link\" id=\"twoRec\" type=\"button\">Non ho il telefono: uso un codice di recupero</button></div>\n    <div class=\"msg\" id=\"twoMsg\"></div>\n  </section>\n\n  <!-- ATTIVAZIONE 2FA -->\n  <section class=\"card narrow hidden\" id=\"enrollCard\">\n    <h2>ATTIVA LA VERIFICA IN DUE PASSAGGI</h2>\n    <div id=\"enrollStep1\">\n      <ol class=\"help\">\n        <li>Installa sul telefono <b>Google Authenticator</b> o <b>Authy</b>.</li>\n        <li>Premi <b>+</b> → <b>Inserisci una chiave di configurazione</b>. Nome: <i>Kairos Store</i>. Chiave: quella qui sotto (basata sull'ora).</li>\n        <li>Scrivi qui il codice di 6 cifre che compare.</li>\n      </ol>\n      <div class=\"secret\" id=\"enSecret\">…</div>\n      <div class=\"actions\"><a class=\"btn ghost small\" id=\"enLink\" href=\"#\">APRI NELL'APP (DA TELEFONO)</a><button class=\"btn ghost small\" id=\"enCopy\" type=\"button\">COPIA CHIAVE</button></div>\n      <label for=\"enCode\">Codice di 6 cifre</label>\n      <input type=\"text\" id=\"enCode\" class=\"code-in\" inputmode=\"numeric\" autocomplete=\"one-time-code\" maxlength=\"6\" placeholder=\"••••••\">\n      <div class=\"actions\"><button class=\"btn\" id=\"enBtn\" type=\"button\">ATTIVA</button></div>\n      <div class=\"msg\" id=\"enMsg\"></div>\n    </div>\n    <div id=\"enrollStep2\" class=\"hidden\">\n      <p class=\"help\"><b>Salva questi 10 codici di recupero</b> in un posto sicuro (non sul telefono con l'app). Ognuno funziona una volta sola, se perdi il telefono.</p>\n      <div class=\"codes\" id=\"enCodes\"></div>\n      <div class=\"actions\"><button class=\"btn ghost small\" id=\"enCodesCopy\" type=\"button\">COPIA I CODICI</button><button class=\"btn\" id=\"enDone\" type=\"button\">LI HO SALVATI</button></div>\n    </div>\n  </section>\n\n  <!-- BLOCCO DI EMERGENZA -->\n  <section class=\"card narrow hidden danger-zone\" id=\"lockCard\">\n    <h2 style=\"color:var(--bad)\">PANNELLO BLOCCATO</h2>\n    <p class=\"help\">È attivo il <b>blocco di emergenza</b>: nessuno può entrare. Per sbloccarlo servono tutti i dati qui sotto.</p>\n    <label for=\"ulKey\">SETUP_KEY</label><input type=\"password\" id=\"ulKey\" autocomplete=\"off\">\n    <label for=\"ulUser\">Username</label><input type=\"text\" id=\"ulUser\" autocomplete=\"username\" spellcheck=\"false\">\n    <label for=\"ulPass\">Password</label><input type=\"password\" id=\"ulPass\" autocomplete=\"current-password\">\n    <label for=\"ulCode\">Codice di verifica (o codice di recupero)</label><input type=\"text\" id=\"ulCode\" class=\"code-in\" inputmode=\"numeric\" maxlength=\"9\" autocomplete=\"one-time-code\">\n    <div class=\"ts\" id=\"tsUnlock\"></div>\n    <div class=\"actions\"><button class=\"btn danger\" id=\"ulBtn\" type=\"button\">SBLOCCA</button></div>\n    <div class=\"msg\" id=\"ulMsg\"></div>\n  </section>\n\n  <!-- PANNELLO -->\n  <div id=\"main\" class=\"hidden\">\n    <div class=\"tabs\"><button type=\"button\" id=\"tabApps\" class=\"on\">APP</button><button type=\"button\" id=\"tabSec\">SICUREZZA</button></div>\n\n    <div id=\"paneApps\">\n      <section class=\"card\">\n        <h2>STORE</h2>\n        <div class=\"row\">\n          <div><label for=\"sName\">Nome dello store</label><input type=\"text\" id=\"sName\" maxlength=\"40\"></div>\n          <div><label for=\"sContact\">Contatto <small>(facoltativo, in fondo alla pagina)</small></label><input type=\"text\" id=\"sContact\" maxlength=\"80\" placeholder=\"es. Supporto: tuamail@esempio.it\"></div>\n        </div>\n        <label for=\"sTag\">Frasi sotto il nome <small>(una per riga, si alternano)</small></label>\n        <textarea id=\"sTag\" rows=\"3\"></textarea>\n      </section>\n      <section class=\"card\">\n        <div style=\"display:flex;align-items:center;gap:12px;margin-bottom:14px\">\n          <h2 style=\"margin:0;flex:1\">APP <span id=\"appCount\" style=\"color:var(--muted)\"></span></h2>\n          <button class=\"btn small\" id=\"addBtn\" type=\"button\">+ AGGIUNGI APP</button>\n        </div>\n        <div class=\"apps\" id=\"apps\"></div>\n      </section>\n    </div>\n\n    <div id=\"paneSec\" class=\"hidden\">\n      <section class=\"card\">\n        <h2>STATO</h2>\n        <div class=\"info\" id=\"secInfo\"></div>\n      </section>\n      <section class=\"card\">\n        <h2>CAMBIA PASSWORD</h2>\n        <div class=\"row\">\n          <div><label for=\"cpOld\">Password attuale</label><input type=\"password\" id=\"cpOld\" autocomplete=\"current-password\"></div>\n          <div><label for=\"cpCode\">Codice di verifica</label><input type=\"text\" id=\"cpCode\" inputmode=\"numeric\" maxlength=\"9\" autocomplete=\"one-time-code\"></div>\n          <div><label for=\"cpNew\">Nuova password <small>(min. 10)</small></label><input type=\"password\" id=\"cpNew\" autocomplete=\"new-password\"></div>\n          <div><label for=\"cpNew2\">Ripeti la nuova password</label><input type=\"password\" id=\"cpNew2\" autocomplete=\"new-password\"></div>\n        </div>\n        <p class=\"help\">Gli altri dispositivi verranno disconnessi.</p>\n        <div class=\"actions\"><button class=\"btn small\" id=\"cpBtn\" type=\"button\">CAMBIA PASSWORD</button></div>\n        <div class=\"msg\" id=\"cpMsg\"></div>\n      </section>\n      <section class=\"card\">\n        <h2>CODICI DI RECUPERO</h2>\n        <p class=\"help\" id=\"rcInfo\"></p>\n        <div class=\"row\">\n          <div><label for=\"rcPass\">Password</label><input type=\"password\" id=\"rcPass\" autocomplete=\"current-password\"></div>\n          <div><label for=\"rcCode\">Codice di verifica</label><input type=\"text\" id=\"rcCode\" inputmode=\"numeric\" maxlength=\"9\" autocomplete=\"one-time-code\"></div>\n        </div>\n        <div class=\"actions\"><button class=\"btn small\" id=\"rcBtn\" type=\"button\">GENERA NUOVI CODICI</button></div>\n        <div class=\"msg\" id=\"rcMsg\"></div>\n        <div class=\"codes hidden\" id=\"rcCodes\"></div>\n      </section>\n      <section class=\"card danger-zone\">\n        <h2 style=\"color:#fda4af\">DISPOSITIVI ED EMERGENZA</h2>\n        <p class=\"help\"><b>Esci da tutti i dispositivi</b>: disconnette ogni telefono e PC, compreso questo.<br>\n          <b>Blocco di emergenza</b>: disconnette tutti e impedisce ogni accesso finché non lo sblocchi con SETUP_KEY, password e codice.</p>\n        <div class=\"actions\"><button class=\"btn ghost small\" id=\"allBtn\" type=\"button\">ESCI DA TUTTI I DISPOSITIVI</button>\n          <button class=\"btn danger small\" id=\"lockBtn\" type=\"button\">BLOCCO DI EMERGENZA</button></div>\n      </section>\n      <section class=\"card\">\n        <h2>REGISTRO ACCESSI</h2>\n        <table class=\"log\" id=\"logTable\"></table>\n      </section>\n    </div>\n  </div>\n</div>\n\n<div class=\"savebar hidden\" id=\"savebar\">\n  <span class=\"st\" id=\"saveSt\">Nessuna modifica</span>\n  <button class=\"btn ghost small\" id=\"undoBtn\" type=\"button\" disabled>ANNULLA MODIFICHE</button>\n  <button class=\"btn\" id=\"saveBtn\" type=\"button\" disabled>SALVA E PUBBLICA</button>\n</div>\n\n<div class=\"modal\" id=\"modal\" role=\"dialog\" aria-modal=\"true\">\n  <div class=\"box\"><h3 id=\"mTitle\"></h3><p id=\"mText\"></p>\n    <div class=\"actions\"><button class=\"btn ghost small\" id=\"mNo\" type=\"button\">ANNULLA</button><button class=\"btn danger small\" id=\"mYes\" type=\"button\">CONFERMA</button></div>\n  </div>\n</div>\n<input type=\"file\" id=\"fileIn\" accept=\"image/*\" class=\"hidden\">\n<script src=\"/app.js\"></script>\n</body>\n</html>\n";
const APPJS = "\"use strict\";\n(function () {\nconst $ = id => document.getElementById(id);\nconst FIELDS = { name: \"\", tag: \"\", description: \"\", url: \"\", code: \"\", icon: \"\", glow: \"#22d3ee\", pkg: \"\", light: false, hidden: false };\nlet ST = {}, data = null, saved = \"\", sha = \"\", iconFiles = [], openIdx = -1, iconTarget = -1, ticket = \"\", useRec = false;\nconst localIcons = {};\nconst tsWidgets = {};\n\n// immagini che non si caricano: nascoste (niente codice inline, per la protezione CSP)\ndocument.addEventListener(\"error\", e => { if (e.target && e.target.tagName === \"IMG\") e.target.style.display = \"none\"; }, true);\n\n// ---------- chiamate al Worker ----------\nasync function api(path, body, method) {\n  const opt = { method: method || (body ? \"POST\" : \"GET\"), headers: { \"X-Kairos\": \"1\" }, credentials: \"same-origin\", cache: \"no-store\" };\n  if (body) { opt.headers[\"Content-Type\"] = \"application/json\"; opt.body = JSON.stringify(body); }\n  let r, j = {};\n  try { r = await fetch(\"/api/\" + path, opt); } catch (e) { throw Object.assign(new Error(\"Connessione assente. Controlla la rete e riprova.\"), { status: 0 }); }\n  try { j = await r.json(); } catch (e) {}\n  if (!r.ok) {\n    const e = Object.assign(new Error(j.error || (\"Errore \" + r.status)), { status: r.status, data: j });\n    if (j.relogin && ![\"login\", \"login/2fa\", \"setup\", \"unlock\"].includes(path)) { setTimeout(() => boot(\"Sessione scaduta: accedi di nuovo.\"), 0); }\n    if (j.locked) setTimeout(() => boot(), 0);\n    throw e;\n  }\n  return j;\n}\nconst msg = (id, text, cls) => { const m = $(id); m.className = \"msg \" + (cls || \"\"); m.textContent = text || \"\"; };\nconst busy = (id, html) => { const m = $(id); m.className = \"msg\"; m.innerHTML = '<span class=\"spin\"></span> ' + html; };\n\n// ---------- Turnstile (se attivato nel Worker) ----------\nlet tsLoading = null;\nfunction loadTs() {\n  if (!ST.turnstile) return Promise.resolve(false);\n  if (window.turnstile) return Promise.resolve(true);\n  if (!tsLoading) tsLoading = new Promise(res => { const s = document.createElement(\"script\"); s.src = \"https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit\"; s.onload = () => res(true); s.onerror = () => res(false); document.head.appendChild(s); });\n  return tsLoading;\n}\nasync function renderTs(id) {\n  if (!(await loadTs())) return;\n  if (tsWidgets[id] !== undefined) { try { turnstile.reset(tsWidgets[id]); } catch (e) {} return; }\n  tsWidgets[id] = turnstile.render(\"#\" + id, { sitekey: ST.turnstile, theme: \"dark\", language: \"it\" });\n}\nfunction tsToken(id) { try { return tsWidgets[id] !== undefined ? turnstile.getResponse(tsWidgets[id]) || \"\" : \"\"; } catch (e) { return \"\"; } }\nfunction tsReset(id) { try { if (tsWidgets[id] !== undefined) turnstile.reset(tsWidgets[id]); } catch (e) {} }\n\n// ---------- schermate ----------\nconst SCREENS = [\"loading\", \"setupCard\", \"loginCard\", \"twoCard\", \"enrollCard\", \"lockCard\", \"main\"];\nfunction show(id) {\n  SCREENS.forEach(s => $(s).classList.toggle(\"hidden\", s !== id));\n  $(\"savebar\").classList.toggle(\"hidden\", id !== \"main\");\n  $(\"logoutBtn\").classList.toggle(\"hidden\", !(id === \"main\" || id === \"enrollCard\"));\n  const f = { setupCard: \"suKey\", loginCard: \"liUser\", twoCard: \"twoCode\", lockCard: \"ulKey\" }[id];\n  if (f) setTimeout(() => { const el = $(f); if (el && !el.value) el.focus(); else if (id === \"loginCard\") $(\"liPass\").focus(); }, 30);\n}\nasync function boot(note) {\n  try { ST = await api(\"state\"); } catch (e) { $(\"loading\").textContent = e.message; show(\"loading\"); return; }\n  $(\"storeLink\").href = ST.storeUrl;\n  if (!ST.setup) { show(\"setupCard\"); renderTs(\"tsSetup\"); return; }\n  if (ST.locked) { show(\"lockCard\"); renderTs(\"tsUnlock\"); return; }\n  if (!ST.logged) { show(\"loginCard\"); renderTs(\"tsLogin\"); if (note) msg(\"liMsg\", note, \"bad\"); return; }\n  if (!ST.enrolled) { startEnroll(); return; }\n  show(\"main\"); loadData();\n}\nfunction enterSubmit(ids, fn) { ids.forEach(id => $(id).addEventListener(\"keydown\", e => { if (e.key === \"Enter\") fn(); })); }\n\n// primo avvio\nasync function doSetup() {\n  if ($(\"suPass\").value !== $(\"suPass2\").value) return msg(\"suMsg\", \"Le due password non coincidono.\", \"bad\");\n  busy(\"suMsg\", \"Creo l'accesso…\"); $(\"suBtn\").disabled = true;\n  try {\n    await api(\"setup\", { key: $(\"suKey\").value, username: $(\"suUser\").value.trim(), password: $(\"suPass\").value, ts: tsToken(\"tsSetup\") });\n    $(\"suKey\").value = $(\"suPass\").value = $(\"suPass2\").value = \"\"; msg(\"suMsg\", \"\");\n    startEnroll();\n  } catch (e) { msg(\"suMsg\", e.message, \"bad\"); tsReset(\"tsSetup\"); } finally { $(\"suBtn\").disabled = false; }\n}\n$(\"suBtn\").onclick = doSetup; enterSubmit([\"suPass2\"], doSetup);\n\n// accesso\nasync function doLogin() {\n  busy(\"liMsg\", \"Controllo…\"); $(\"liBtn\").disabled = true;\n  try {\n    const r = await api(\"login\", { username: $(\"liUser\").value.trim(), password: $(\"liPass\").value, remember: $(\"liRemember\").checked, key: $(\"liKey\").value, ts: tsToken(\"tsLogin\") });\n    $(\"liPass\").value = \"\"; $(\"liKey\").value = \"\"; msg(\"liMsg\", \"\");\n    if (r.step === \"2fa\") { ticket = r.ticket; useRec = false; setRecMode(); $(\"twoCode\").value = \"\"; msg(\"twoMsg\", \"\"); show(\"twoCard\"); return; }\n    if (r.enroll) { startEnroll(); return; }\n    boot();\n  } catch (e) {\n    if (e.data && e.data.needKey) $(\"liKeyBox\").classList.remove(\"hidden\");\n    msg(\"liMsg\", e.message, \"bad\"); tsReset(\"tsLogin\");\n  } finally { $(\"liBtn\").disabled = false; }\n}\n$(\"liBtn\").onclick = doLogin; enterSubmit([\"liPass\", \"liKey\"], doLogin);\n\nfunction setRecMode() {\n  $(\"twoHelp\").innerHTML = useRec ? \"Scrivi uno dei tuoi <b>codici di recupero</b> (es. ABCD-EFGH). Ogni codice vale una volta sola.\"\n    : \"Apri Google Authenticator (o Authy) e scrivi il codice di 6 cifre di <b>Kairos Store</b>.\";\n  $(\"twoCode\").inputMode = useRec ? \"text\" : \"numeric\"; $(\"twoCode\").placeholder = useRec ? \"XXXX-XXXX\" : \"••••••\";\n  $(\"twoRec\").textContent = useRec ? \"Uso il codice dell'app\" : \"Non ho il telefono: uso un codice di recupero\";\n  $(\"twoCode\").focus();\n}\n$(\"twoRec\").onclick = () => { useRec = !useRec; $(\"twoCode\").value = \"\"; setRecMode(); };\nasync function doTwo() {\n  busy(\"twoMsg\", \"Controllo…\"); $(\"twoBtn\").disabled = true;\n  try {\n    const r = await api(\"login/2fa\", { ticket, code: $(\"twoCode\").value });\n    ticket = \"\"; msg(\"twoMsg\", \"\");\n    if (r.recoveryLeft <= 3) setTimeout(() => toastSave(\"Ti restano \" + r.recoveryLeft + \" codici di recupero: generane di nuovi in Sicurezza.\", \"bad\"), 800);\n    boot();\n  } catch (e) {\n    msg(\"twoMsg\", e.message, \"bad\"); $(\"twoCode\").value = \"\";\n    if (e.data && e.data.restart) setTimeout(() => { show(\"loginCard\"); renderTs(\"tsLogin\"); msg(\"liMsg\", e.message, \"bad\"); }, 1400);\n  } finally { $(\"twoBtn\").disabled = false; }\n}\n$(\"twoBtn\").onclick = doTwo; enterSubmit([\"twoCode\"], doTwo);\n$(\"twoCode\").addEventListener(\"input\", () => { if (!useRec && /^\\d{6}$/.test($(\"twoCode\").value.trim())) doTwo(); });\n\n// attivazione verifica in due passaggi\nasync function startEnroll() {\n  show(\"enrollCard\"); $(\"enrollStep1\").classList.remove(\"hidden\"); $(\"enrollStep2\").classList.add(\"hidden\");\n  $(\"enCode\").value = \"\"; msg(\"enMsg\", \"\");\n  try { const r = await api(\"2fa/start\", {}); $(\"enSecret\").textContent = r.secret.replace(/(.{4})/g, \"$1 \").trim(); $(\"enLink\").href = r.uri; $(\"enCode\").focus(); }\n  catch (e) { msg(\"enMsg\", e.message, \"bad\"); }\n}\n$(\"enCopy\").onclick = () => copy($(\"enSecret\").textContent.replace(/\\s/g, \"\"), $(\"enCopy\"));\nasync function doEnroll() {\n  busy(\"enMsg\", \"Controllo…\"); $(\"enBtn\").disabled = true;\n  try {\n    const r = await api(\"2fa/confirm\", { code: $(\"enCode\").value });\n    $(\"enCodes\").innerHTML = r.recovery.map(c => \"<span>\" + c + \"</span>\").join(\"\");\n    $(\"enCodes\").dataset.all = r.recovery.join(\"\\n\");\n    $(\"enrollStep1\").classList.add(\"hidden\"); $(\"enrollStep2\").classList.remove(\"hidden\");\n  } catch (e) { msg(\"enMsg\", e.message, \"bad\"); } finally { $(\"enBtn\").disabled = false; }\n}\n$(\"enBtn\").onclick = doEnroll; enterSubmit([\"enCode\"], doEnroll);\n$(\"enCodesCopy\").onclick = () => copy($(\"enCodes\").dataset.all, $(\"enCodesCopy\"));\n$(\"enDone\").onclick = () => boot();\n\n// sblocco di emergenza\nasync function doUnlock() {\n  busy(\"ulMsg\", \"Controllo…\"); $(\"ulBtn\").disabled = true;\n  try {\n    await api(\"unlock\", { key: $(\"ulKey\").value, username: $(\"ulUser\").value.trim(), password: $(\"ulPass\").value, code: $(\"ulCode\").value, ts: tsToken(\"tsUnlock\") });\n    [\"ulKey\", \"ulPass\", \"ulCode\"].forEach(i => $(i).value = \"\"); boot();\n  } catch (e) { msg(\"ulMsg\", e.message, \"bad\"); tsReset(\"tsUnlock\"); } finally { $(\"ulBtn\").disabled = false; }\n}\n$(\"ulBtn\").onclick = doUnlock; enterSubmit([\"ulCode\"], doUnlock);\n\n$(\"logoutBtn\").onclick = () => {\n  const go = async () => { try { await api(\"logout\", {}); } catch (e) {} data = null; boot(); };\n  if (data && isDirty()) ask(\"Uscire senza salvare?\", \"Le modifiche non salvate andranno perse.\", \"ESCI\", go); else go();\n};\n\nfunction copy(text, btn) {\n  const done = () => { const t = btn.textContent; btn.textContent = \"COPIATO ✓\"; setTimeout(() => btn.textContent = t, 1500); };\n  if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, () => {});\n}\n\n// ---------- schede ----------\n$(\"tabApps\").onclick = () => tab(\"apps\"); $(\"tabSec\").onclick = () => tab(\"sec\");\nfunction tab(t) {\n  $(\"tabApps\").classList.toggle(\"on\", t === \"apps\"); $(\"tabSec\").classList.toggle(\"on\", t === \"sec\");\n  $(\"paneApps\").classList.toggle(\"hidden\", t !== \"apps\"); $(\"paneSec\").classList.toggle(\"hidden\", t !== \"sec\");\n  if (t === \"sec\") loadSec();\n}\n\n// ---------- dati delle app ----------\nasync function loadData() {\n  $(\"apps\").innerHTML = '<div class=\"msg\"><span class=\"spin\"></span> Carico le app…</div>';\n  try {\n    const r = await api(\"data\");\n    sha = r.sha; iconFiles = r.icons || [];\n    data = r.data || { store: {}, apps: [] };\n    data.store = Object.assign({ name: \"\", taglines: [], contact: \"\" }, data.store || {});\n    data.apps = (data.apps || []).map(a => Object.assign({}, FIELDS, a));\n    saved = JSON.stringify(data); openIdx = -1; renderAll();\n    if (r.recoveryLeft <= 3) toastSave(\"Ti restano \" + r.recoveryLeft + \" codici di recupero: generane di nuovi in Sicurezza.\", \"bad\");\n  } catch (e) { $(\"apps\").innerHTML = \"\"; const d = document.createElement(\"div\"); d.className = \"msg bad\"; d.textContent = e.message; $(\"apps\").appendChild(d); }\n}\nconst esc = s => String(s == null ? \"\" : s).replace(/[&<>\"']/g, c => ({ \"&\": \"&amp;\", \"<\": \"&lt;\", \">\": \"&gt;\", '\"': \"&quot;\", \"'\": \"&#39;\" }[c]));\nconst iconSrc = p => localIcons[p] || (p ? ST.storeUrl + p : \"\");\nfunction iconHtml(a) { const src = iconSrc(a.icon); return src ? `<img alt=\"\" src=\"${esc(src)}\">` : esc((a.name || \"?\").charAt(0)); }\nfunction renderAll() {\n  $(\"sName\").value = data.store.name || \"\"; $(\"sContact\").value = data.store.contact || \"\";\n  $(\"sTag\").value = (data.store.taglines || []).join(\"\\n\");\n  renderApps(); updateBar();\n}\nconst validUrl = u => /^https?:\\/\\/[^\\s\"'<>\\\\]+\\.[^\\s\"'<>\\\\]+$/i.test(u || \"\");\nfunction appProblems(a) {\n  const p = [];\n  if (!String(a.name || \"\").trim()) p.push(\"Manca il nome\");\n  if (a.url ? !validUrl(a.url) : !a.hidden) p.push(\"Manca il link all'APK (deve iniziare con https://)\");\n  return p;\n}\nfunction renderApps() {\n  const box = $(\"apps\"); box.innerHTML = \"\";\n  $(\"appCount\").textContent = \"(\" + data.apps.filter(a => !a.hidden).length + \" visibili su \" + data.apps.length + \")\";\n  if (!data.apps.length) box.innerHTML = '<p class=\"help\">Nessuna app. Premi <b>+ AGGIUNGI APP</b>.</p>';\n  data.apps.forEach((a, i) => {\n    const el = document.createElement(\"div\");\n    el.className = \"app\" + (i === openIdx ? \" open\" : \"\") + (a.hidden ? \" hiddenapp\" : \"\");\n    el.style.setProperty(\"--g\", /^#[0-9a-f]{6}$/i.test(a.glow) ? a.glow : \"#22d3ee\");\n    const problems = appProblems(a);\n    el.innerHTML = `\n      <div class=\"head\" data-act=\"toggle\" data-i=\"${i}\">\n        <div class=\"ic\">${iconHtml(a)}</div>\n        <div class=\"tt\"><div class=\"nm\">${esc(a.name || \"Nuova app\")}${a.hidden ? '<span class=\"badge\">NASCOSTA</span>' : \"\"}${problems.length ? '<span class=\"badge warn\">DA COMPLETARE</span>' : \"\"}</div>\n          <div class=\"sub\">${esc(a.description || a.url || \"—\")}</div></div>\n        <div class=\"mv\"><button type=\"button\" data-act=\"up\" data-i=\"${i}\" title=\"Sposta su\" ${i === 0 ? \"disabled\" : \"\"}>↑</button>\n          <button type=\"button\" data-act=\"down\" data-i=\"${i}\" title=\"Sposta giù\" ${i === data.apps.length - 1 ? \"disabled\" : \"\"}>↓</button></div>\n        <svg class=\"chev\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.4\"><path d=\"M6 9l6 6 6-6\"/></svg>\n      </div>\n      <div class=\"body\">\n        <div class=\"row3\">\n          <div><label>Nome *</label><input type=\"text\" data-f=\"name\" data-i=\"${i}\" value=\"${esc(a.name)}\" maxlength=\"30\"></div>\n          <div><label>Etichetta <small>(es. PLAYER)</small></label><input type=\"text\" data-f=\"tag\" data-i=\"${i}\" value=\"${esc(a.tag)}\" maxlength=\"14\"></div>\n          <div><label>Colore bagliore</label><div class=\"color\"><input type=\"color\" data-f=\"glow\" data-i=\"${i}\" value=\"${esc(/^#[0-9a-f]{6}$/i.test(a.glow) ? a.glow : \"#22d3ee\")}\"><span style=\"color:var(--muted);font-size:14px\">${esc(a.glow)}</span></div></div>\n        </div>\n        <label>Descrizione <small>(una frase breve)</small></label>\n        <input type=\"text\" data-f=\"description\" data-i=\"${i}\" value=\"${esc(a.description)}\" maxlength=\"90\">\n        <label>Link diretto all'APK * <small>(deve scaricare il file, non aprire una pagina)</small></label>\n        <input type=\"url\" data-f=\"url\" data-i=\"${i}\" value=\"${esc(a.url)}\" placeholder=\"https://…/app.apk\" maxlength=\"500\" class=\"${a.url && !validUrl(a.url) ? \"err\" : \"\"}\">\n        <div class=\"row\">\n          <div><label>Codice Downloader <small>(solo promemoria, non visibile)</small></label><input type=\"text\" data-f=\"code\" data-i=\"${i}\" value=\"${esc(a.code)}\" maxlength=\"12\"></div>\n          <div><label>Nome pacchetto Android <small>(facoltativo, per Disinstalla)</small></label><input type=\"text\" data-f=\"pkg\" data-i=\"${i}\" value=\"${esc(a.pkg)}\" placeholder=\"com.esempio.player\" spellcheck=\"false\" maxlength=\"100\"></div>\n        </div>\n        <label>Icona</label>\n        <div class=\"iconrow\">\n          <div class=\"big${a.light ? \" contain\" : \"\"}\">${iconHtml(a)}</div>\n          <div>\n            <button class=\"btn small\" type=\"button\" data-act=\"upload\" data-i=\"${i}\">CARICA IMMAGINE</button>\n            <div style=\"margin-top:8px\"><select data-f=\"icon\" data-i=\"${i}\" aria-label=\"Scegli un'icona esistente\">\n              <option value=\"\">— scegli un'icona già caricata —</option>\n              ${[...new Set([...iconFiles, ...Object.keys(localIcons), a.icon].filter(Boolean))].map(p => `<option value=\"${esc(p)}\" ${p === a.icon ? \"selected\" : \"\"}>${esc(p.replace(\"icons/\", \"\"))}</option>`).join(\"\")}\n            </select></div>\n            <label class=\"check\" style=\"margin-top:8px\"><input type=\"checkbox\" data-f=\"light\" data-i=\"${i}\" ${a.light ? \"checked\" : \"\"}> Logo con sfondo trasparente (mostralo intero)</label>\n          </div>\n        </div>\n        <label class=\"check\"><input type=\"checkbox\" data-f=\"hidden\" data-i=\"${i}\" ${a.hidden ? \"checked\" : \"\"}> Nascondi dallo store (resta qui, la puoi riattivare)</label>\n        <div class=\"actions\"><button class=\"btn danger small\" type=\"button\" data-act=\"del\" data-i=\"${i}\">ELIMINA APP</button></div>\n        ${problems.length ? `<div class=\"msg bad\">${problems.map(esc).join(\" · \")}</div>` : \"\"}\n      </div>`;\n    box.appendChild(el);\n  });\n}\n$(\"apps\").addEventListener(\"input\", e => {\n  const t = e.target, i = +t.dataset.i, f = t.dataset.f; if (!f || isNaN(i)) return;\n  const a = data.apps[i]; a[f] = t.type === \"checkbox\" ? t.checked : t.value;\n  if (f === \"url\") t.classList.toggle(\"err\", !!a.url && !validUrl(a.url));\n  refreshHead(i);\n  updateBar();\n});\n$(\"apps\").addEventListener(\"change\", e => { const f = e.target.dataset.f; if (!f) return; if ([\"icon\", \"light\", \"hidden\", \"glow\"].includes(f)) renderApps(); else refreshHead(+e.target.dataset.i); updateBar(); });\nfunction refreshHead(i) {\n  const el = $(\"apps\").children[i], a = data.apps[i]; if (!el) return;\n  el.style.setProperty(\"--g\", /^#[0-9a-f]{6}$/i.test(a.glow) ? a.glow : \"#22d3ee\");\n  const problems = appProblems(a);\n  el.querySelector(\".nm\").innerHTML = esc(a.name || \"Nuova app\") + (a.hidden ? '<span class=\"badge\">NASCOSTA</span>' : \"\") + (problems.length ? '<span class=\"badge warn\">DA COMPLETARE</span>' : \"\");\n  el.querySelector(\".sub\").textContent = a.description || a.url || \"—\";\n  const sw = el.querySelector(\".color span\"); if (sw) sw.textContent = a.glow;\n  let pm = el.querySelector(\".body > .msg.bad\");\n  if (problems.length) { if (!pm) { pm = document.createElement(\"div\"); pm.className = \"msg bad\"; el.querySelector(\".body\").appendChild(pm); } pm.textContent = problems.join(\" · \"); }\n  else if (pm) pm.remove();\n}\n$(\"apps\").addEventListener(\"click\", e => {\n  const b = e.target.closest(\"[data-act]\"); if (!b) return;\n  const i = +b.dataset.i, act = b.dataset.act;\n  if (act === \"toggle\") { if (e.target.closest(\".mv\")) return; openIdx = openIdx === i ? -1 : i; renderApps(); return; }\n  e.stopPropagation();\n  if (act === \"up\" || act === \"down\") {\n    const j = act === \"up\" ? i - 1 : i + 1; if (j < 0 || j >= data.apps.length) return;\n    [data.apps[i], data.apps[j]] = [data.apps[j], data.apps[i]];\n    if (openIdx === i) openIdx = j; else if (openIdx === j) openIdx = i;\n    renderApps(); updateBar();\n  } else if (act === \"del\") {\n    ask(\"Eliminare \" + (data.apps[i].name || \"questa app\") + \"?\", \"Sparirà dallo store dopo il salvataggio. Se vuoi solo toglierla per un po', usa \\\"Nascondi\\\".\", \"ELIMINA\", () => {\n      data.apps.splice(i, 1); openIdx = -1; renderApps(); updateBar();\n    });\n  } else if (act === \"upload\") { iconTarget = i; $(\"fileIn\").value = \"\"; $(\"fileIn\").click(); }\n});\n[\"sName\", \"sContact\", \"sTag\"].forEach(id => $(id).addEventListener(\"input\", () => {\n  data.store.name = $(\"sName\").value; data.store.contact = $(\"sContact\").value;\n  data.store.taglines = $(\"sTag\").value.split(\"\\n\").map(s => s.trim()).filter(Boolean);\n  updateBar();\n}));\n$(\"addBtn\").onclick = () => {\n  const palette = [\"#22d3ee\", \"#8b5cf6\", \"#ec4899\", \"#34d399\", \"#f59e0b\", \"#60a5fa\", \"#f43f5e\"];\n  data.apps.push(Object.assign({}, FIELDS, { tag: \"PLAYER\", glow: palette[data.apps.length % palette.length] }));\n  openIdx = data.apps.length - 1; renderApps(); updateBar();\n  setTimeout(() => { const el = $(\"apps\").lastElementChild; el.scrollIntoView({ behavior: \"smooth\", block: \"center\" }); el.querySelector('input[data-f=\"name\"]').focus(); }, 50);\n};\n\n// icona: ridimensionata a 144×144 nel browser e salvata su GitHub dal Worker\nconst b64 = bytes => { let s = \"\"; for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)); return btoa(s); };\n$(\"fileIn\").addEventListener(\"change\", async () => {\n  const file = $(\"fileIn\").files[0], i = iconTarget; if (!file || i < 0) return;\n  const a = data.apps[i];\n  setSt('<span class=\"spin\"></span> Carico l\\'icona…', \"\");\n  try {\n    const url = URL.createObjectURL(file);\n    const img = await new Promise((res, rej) => { const im = new Image(); im.onload = () => res(im); im.onerror = rej; im.src = url; });\n    const S = 144, cv = document.createElement(\"canvas\"); cv.width = cv.height = S;\n    const cx = cv.getContext(\"2d\"), r = Math.max(S / img.width, S / img.height), w = img.width * r, h = img.height * r;\n    cx.drawImage(img, (S - w) / 2, (S - h) / 2, w, h); URL.revokeObjectURL(url);\n    let blob = await new Promise(res => cv.toBlob(res, \"image/webp\", .82)), type = \"webp\";\n    if (!blob || blob.type !== \"image/webp\") { blob = await new Promise(res => cv.toBlob(res, \"image/png\")); type = \"png\"; }\n    const r2 = await api(\"icon\", { name: a.name || \"app\", type, data: b64(new Uint8Array(await blob.arrayBuffer())) });\n    localIcons[r2.path] = cv.toDataURL(); iconFiles.push(r2.path);\n    a.icon = r2.path; renderApps(); updateBar();\n    setSt(\"Icona caricata. Premi SALVA E PUBBLICA per applicarla.\", \"dirty\");\n  } catch (e) { setSt(e.status !== undefined ? esc(e.message) : \"Immagine non valida: usa un file PNG, JPG o WEBP.\", \"bad\"); }\n});\n\n// ---------- salvataggio ----------\nconst isDirty = () => !!data && JSON.stringify(data) !== saved;\nfunction setSt(html, cls) { const s = $(\"saveSt\"); s.className = \"st \" + (cls || \"\"); s.innerHTML = html; }\nfunction toastSave(text, cls) { setSt(esc(text), cls); }\nfunction updateBar() {\n  if (!data) return;\n  const d = isDirty(), bad = data.apps.some(a => appProblems(a).length);\n  $(\"saveBtn\").disabled = !d || bad; $(\"undoBtn\").disabled = !d;\n  if (d) setSt(bad ? \"Completa i campi segnati prima di salvare (o nascondi l'app).\" : \"Modifiche non salvate\", bad ? \"bad\" : \"dirty\");\n  else if (!$(\"saveSt\").classList.contains(\"ok\") && !$(\"saveSt\").classList.contains(\"bad\")) setSt(\"Nessuna modifica\", \"\");\n}\n$(\"undoBtn\").onclick = () => ask(\"Annullare le modifiche?\", \"Torni all'ultima versione salvata.\", \"ANNULLA MODIFICHE\", () => { data = JSON.parse(saved); openIdx = -1; renderAll(); });\n$(\"saveBtn\").onclick = async () => {\n  $(\"saveBtn\").disabled = true; $(\"undoBtn\").disabled = true; setSt('<span class=\"spin\"></span> Pubblico…', \"\");\n  try {\n    const r = await api(\"data\", { data, sha }, \"PUT\");\n    sha = r.sha; data = r.data; data.apps = data.apps.map(a => Object.assign({}, FIELDS, a)); saved = JSON.stringify(data); renderAll();\n    setSt(\"✓ Pubblicato. Lo store si aggiorna in circa 1 minuto (sulla TV alla prossima apertura o con AGGIORNA).\", \"ok\");\n  } catch (e) { setSt(esc(e.message), \"bad\"); $(\"saveBtn\").disabled = !isDirty(); $(\"undoBtn\").disabled = !isDirty(); }\n};\nwindow.addEventListener(\"beforeunload\", e => { if (isDirty()) { e.preventDefault(); e.returnValue = \"\"; } });\n\n// ---------- sicurezza ----------\nconst fmt = t => new Date(t).toLocaleString(\"it-IT\", { day: \"2-digit\", month: \"2-digit\", year: \"2-digit\", hour: \"2-digit\", minute: \"2-digit\" });\nasync function loadSec() {\n  $(\"secInfo\").innerHTML = '<div><span class=\"spin\"></span> Carico…</div>';\n  try {\n    const r = await api(\"security\");\n    const cell = (k, v) => `<div><b>${k}</b>${v}</div>`;\n    $(\"secInfo\").innerHTML = cell(\"UTENTE\", esc(ST.username)) + cell(\"DISPOSITIVI COLLEGATI\", r.sessions) +\n      cell(\"QUESTO DISPOSITIVO\", r.remember ? \"Ricordato per \" + r.rememberDays + \" giorni\" : \"Esce dopo \" + r.idleMin + \" minuti di inattività\") +\n      cell(\"CODICI DI RECUPERO RIMASTI\", r.recoveryLeft + \" su 10\") +\n      cell(\"AVVISI TELEGRAM\", r.telegram ? \"Attivi\" : \"Non attivi\") + cell(\"ANTI-ROBOT (TURNSTILE)\", r.turnstile ? \"Attivo\" : \"Non attivo\") +\n      cell(\"PAESI CONSENTITI\", r.countries ? esc(r.countries) : \"Tutti\") + cell(\"VERSIONE PANNELLO\", esc(ST.version));\n    $(\"rcInfo\").textContent = \"Ti restano \" + r.recoveryLeft + \" codici su 10. Generandone di nuovi, quelli vecchi smettono di funzionare.\";\n    $(\"logTable\").innerHTML = r.log.length ? r.log.map(l => `<tr><td>${fmt(l.t)}</td><td class=\"${l.ok ? \"\" : \"no\"}\">${esc(l.a)}</td><td>${esc(l.ip)} · ${esc(l.c)}</td></tr>`).join(\"\")\n      : '<tr><td>Nessun evento</td></tr>';\n  } catch (e) { $(\"secInfo\").innerHTML = \"\"; const d = document.createElement(\"div\"); d.textContent = e.message; $(\"secInfo\").appendChild(d); }\n}\n$(\"cpBtn\").onclick = async () => {\n  if ($(\"cpNew\").value !== $(\"cpNew2\").value) return msg(\"cpMsg\", \"Le due password nuove non coincidono.\", \"bad\");\n  busy(\"cpMsg\", \"Cambio…\"); $(\"cpBtn\").disabled = true;\n  try {\n    await api(\"password\", { old: $(\"cpOld\").value, code: $(\"cpCode\").value, password: $(\"cpNew\").value });\n    [\"cpOld\", \"cpCode\", \"cpNew\", \"cpNew2\"].forEach(i => $(i).value = \"\"); msg(\"cpMsg\", \"✓ Password cambiata. Gli altri dispositivi sono stati disconnessi.\", \"ok\"); loadSec();\n  } catch (e) { msg(\"cpMsg\", e.message, \"bad\"); } finally { $(\"cpBtn\").disabled = false; }\n};\n$(\"rcBtn\").onclick = async () => {\n  busy(\"rcMsg\", \"Genero…\"); $(\"rcBtn\").disabled = true;\n  try {\n    const r = await api(\"recovery\", { password: $(\"rcPass\").value, code: $(\"rcCode\").value });\n    $(\"rcPass\").value = $(\"rcCode\").value = \"\";\n    $(\"rcCodes\").innerHTML = r.recovery.map(c => \"<span>\" + c + \"</span>\").join(\"\"); $(\"rcCodes\").classList.remove(\"hidden\");\n    msg(\"rcMsg\", \"✓ Nuovi codici qui sotto: salvali subito, non verranno più mostrati.\", \"ok\"); loadSec();\n  } catch (e) { msg(\"rcMsg\", e.message, \"bad\"); } finally { $(\"rcBtn\").disabled = false; }\n};\n$(\"allBtn\").onclick = () => ask(\"Uscire da tutti i dispositivi?\", \"Verrai disconnesso anche qui e dovrai rientrare con password e codice.\", \"ESCI DA TUTTI\", async () => {\n  try { await api(\"logout-all\", {}); } catch (e) {} data = null; boot();\n});\n$(\"lockBtn\").onclick = () => ask(\"Attivare il blocco di emergenza?\", \"Tutti verranno disconnessi e nessuno potrà entrare finché non sblocchi con SETUP_KEY, password e codice di verifica.\", \"BLOCCA TUTTO\", async () => {\n  try { await api(\"lock\", {}); } catch (e) {} data = null; boot();\n});\n\n// ---------- finestra di conferma ----------\nlet onYes = null;\nfunction ask(title, text, yes, fn) { $(\"mTitle\").textContent = title; $(\"mText\").textContent = text; $(\"mYes\").textContent = yes; onYes = fn; $(\"modal\").classList.add(\"on\"); $(\"mNo\").focus(); }\n$(\"mNo\").onclick = () => { $(\"modal\").classList.remove(\"on\"); onYes = null; };\n$(\"mYes\").onclick = () => { $(\"modal\").classList.remove(\"on\"); const f = onYes; onYes = null; if (f) f(); };\n$(\"modal\").addEventListener(\"click\", e => { if (e.target === $(\"modal\")) $(\"mNo\").click(); });\ndocument.addEventListener(\"keydown\", e => { if (e.key === \"Escape\" && $(\"modal\").classList.contains(\"on\")) $(\"mNo\").click(); });\n\nboot();\n})();\n";

export default {
  async fetch(req, env, ctx) {
    try { return await handle(req, env, ctx); }
    catch (e) { console.error(e && e.stack || e); return json({ error: "Errore interno. Riprova." }, 500); }
  },
};

// ---------------------------------------------------------------- utilità
const te = new TextEncoder();
const hex = buf => [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, "0")).join("");
const unhex = h => new Uint8Array((h.match(/../g) || []).map(x => parseInt(x, 16)));
const sha = async s => hex(await crypto.subtle.digest("SHA-256", te.encode(s)));
const rand = n => { const a = new Uint8Array(n); crypto.getRandomValues(a); return a; };
function eq(a, b) { // confronto a tempo costante
  a = String(a); b = String(b); let d = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) d |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return d === 0;
}
const repo = env => (env.GITHUB_REPO || "mariopiccolo8562-lang/kairos-store").trim();
const storeUrl = env => { let u = (env.STORE_URL || "https://mariopiccolo8562-lang.github.io/kairos-store/").trim(); return u.endsWith("/") ? u : u + "/"; };

function secHeaders(env, extra = {}) {
  let storeOrigin = ""; try { storeOrigin = new URL(storeUrl(env)).origin; } catch (e) {}
  return {
    "Content-Security-Policy": [
      "default-src 'none'", "script-src 'self' https://challenges.cloudflare.com",
      "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com", "font-src https://fonts.gstatic.com",
      "img-src 'self' data: blob: " + storeOrigin, "connect-src 'self'", "frame-src https://challenges.cloudflare.com",
      "frame-ancestors 'none'", "base-uri 'none'", "form-action 'none'",
    ].join("; "),
    "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer", "X-Frame-Options": "DENY",
    "X-Robots-Tag": "noindex, nofollow", "Cache-Control": "no-store",
    "Strict-Transport-Security": "max-age=31536000", "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
    "Cross-Origin-Opener-Policy": "same-origin",
    ...extra,
  };
}
function json(obj, status = 200, headers = {}) {
  return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", ...headers } });
}
const fail = (msg, status = 400, extra = {}) => json({ error: msg, ...extra }, status);
const clientIp = req => req.headers.get("cf-connecting-ip") || "0.0.0.0";
const country = req => ((req.cf && req.cf.country) || req.headers.get("cf-ipcountry") || "XX").toUpperCase();
function countryAllowed(req, env) {
  const list = String(env.ALLOWED_COUNTRIES || "").split(",").map(s => s.trim().toUpperCase()).filter(Boolean);
  return !list.length || list.includes(country(req));
}
function getCookie(req, name) {
  const c = req.headers.get("cookie") || "";
  for (const part of c.split(";")) { const i = part.indexOf("="); if (i > 0 && part.slice(0, i).trim() === name) return part.slice(i + 1).trim(); }
  return "";
}

// ---------------------------------------------------------------- avvisi e registro
function notify(env, ctx, text) {
  if (!env.TELEGRAM_TOKEN || !env.TELEGRAM_CHAT) return;
  const p = fetch("https://api.telegram.org/bot" + env.TELEGRAM_TOKEN + "/sendMessage", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chat_id: env.TELEGRAM_CHAT, text: "🛡 Pannello Kairos Store\n" + text, disable_web_page_preview: true }),
  }).catch(() => {});
  if (ctx && ctx.waitUntil) ctx.waitUntil(p);
}
async function log(env, req, action, ok = true) {
  const list = (await env.DB.get("log", "json")) || [];
  list.unshift({ t: Date.now(), ip: clientIp(req), c: country(req), a: action, ok: !!ok });
  await env.DB.put("log", JSON.stringify(list.slice(0, 200)));
}

// ---------------------------------------------------------------- limite tentativi
async function isBlocked(env, req) {
  const r = await env.DB.get("rl:" + clientIp(req), "json");
  if (r && r.n >= MAX_FAIL_IP) return true;
  const g = await env.DB.get("rl:__all", "json");
  return !!(g && g.n >= MAX_FAIL_ALL);
}
async function addFail(env, ctx, req, what) {
  const ip = clientIp(req);
  const r = (await env.DB.get("rl:" + ip, "json")) || { n: 0 };
  r.n++; await env.DB.put("rl:" + ip, JSON.stringify(r), { expirationTtl: FAIL_WINDOW });
  const g = (await env.DB.get("rl:__all", "json")) || { n: 0, since: Date.now() };
  g.n++; await env.DB.put("rl:__all", JSON.stringify(g), { expirationTtl: 3600 });
  await log(env, req, what, false);
  if (r.n === MAX_FAIL_IP) notify(env, ctx, "⚠️ IP bloccato per 15 minuti dopo " + MAX_FAIL_IP + " tentativi sbagliati\nIP: " + ip + " (" + country(req) + ")");
  if (g.n === MAX_FAIL_ALL) notify(env, ctx, "🚨 Troppi tentativi sbagliati da IP diversi: accesso sospeso per un'ora.");
}
const clearFails = (env, req) => env.DB.delete("rl:" + clientIp(req));
const BLOCKED_MSG = "Troppi tentativi. Riprova tra 15 minuti.";

async function turnstileOk(env, req, token) {
  if (!env.TURNSTILE_SECRET) return true;
  if (!token) return false;
  try {
    const f = new FormData(); f.append("secret", env.TURNSTILE_SECRET); f.append("response", String(token)); f.append("remoteip", clientIp(req));
    const r = await fetch("https://challenges.cloudflare.com/turnstile/v0/siteverify", { method: "POST", body: f });
    return !!(await r.json()).success;
  } catch (e) { return false; }
}

// ---------------------------------------------------------------- password e 2FA
async function pbkdf2(pw, saltHex, iter) {
  const k = await crypto.subtle.importKey("raw", te.encode(pw), "PBKDF2", false, ["deriveBits"]);
  return hex(await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: unhex(saltHex), iterations: iter }, k, 256));
}
async function makePass(pw) { const salt = hex(rand(16)), iter = 30000; /* adatto al limite di CPU del piano gratuito */ return { salt, iter, hash: await pbkdf2(pw, salt, iter) }; }
const DUMMY = { salt: "00112233445566778899aabbccddeeff", iter: 30000, hash: "0".repeat(64) };
async function checkPass(p, pw) { const h = await pbkdf2(String(pw || ""), p.salt, p.iter); return eq(h, p.hash); }
const pwProblem = pw => typeof pw !== "string" || pw.length < 10 ? "La password deve avere almeno 10 caratteri." : pw.length > 200 ? "Password troppo lunga." : "";

const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
function b32enc(bytes) { let bits = 0, val = 0, out = ""; for (const b of bytes) { val = (val << 8) | b; bits += 8; while (bits >= 5) { out += B32[(val >>> (bits - 5)) & 31]; bits -= 5; } } if (bits) out += B32[(val << (5 - bits)) & 31]; return out; }
function b32dec(s) { let bits = 0, val = 0; const out = []; for (const ch of String(s).toUpperCase().replace(/[^A-Z2-7]/g, "")) { val = (val << 5) | B32.indexOf(ch); bits += 5; if (bits >= 8) { out.push((val >>> (bits - 8)) & 255); bits -= 8; } } return new Uint8Array(out); }
async function totpAt(secret, step) {
  const key = await crypto.subtle.importKey("raw", b32dec(secret), { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
  const msg = new ArrayBuffer(8), dv = new DataView(msg); dv.setUint32(0, Math.floor(step / 4294967296)); dv.setUint32(4, step >>> 0);
  const h = new Uint8Array(await crypto.subtle.sign("HMAC", key, msg)), o = h[h.length - 1] & 15;
  return String((((h[o] & 127) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3]) % 1000000).padStart(6, "0");
}
// restituisce il passo usato (per impedire il riuso dello stesso codice) oppure 0
async function totpMatch(secret, code, last = 0) {
  code = String(code || "").replace(/\s/g, ""); if (!/^\d{6}$/.test(code)) return 0;
  const now = Math.floor(Date.now() / 30000);
  for (const d of [0, -1, 1]) { const st = now + d; if (st > last && eq(await totpAt(secret, st), code)) return st; }
  return 0;
}
const normRec = c => String(c || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
async function newRecovery() {
  const plain = [], hashes = [];
  for (let i = 0; i < 10; i++) { const s = b32enc(rand(5)).slice(0, 8); plain.push(s.slice(0, 4) + "-" + s.slice(4)); hashes.push(await sha("rec:" + s)); }
  return { plain, hashes };
}
// verifica codice 2FA o codice di recupero; aggiorna l'utente (passo usato / codice consumato)
async function secondFactor(env, ctx, req, user, code) {
  const st = await totpMatch(user.totp.secret, code, user.totp.last || 0);
  if (st) { user.totp.last = st; await env.DB.put("user", JSON.stringify(user)); return "totp"; }
  const n = normRec(code);
  if (n.length === 8) {
    const h = await sha("rec:" + n), i = (user.recovery || []).findIndex(x => eq(x, h));
    if (i >= 0) {
      user.recovery.splice(i, 1); await env.DB.put("user", JSON.stringify(user));
      notify(env, ctx, "🔑 Usato un codice di recupero. Ne restano " + user.recovery.length + ".\nIP: " + clientIp(req) + " (" + country(req) + ")");
      return "recovery";
    }
  }
  return "";
}

// ---------------------------------------------------------------- sessioni
const getUser = env => env.DB.get("user", "json");
async function newSession(env, req, user, remember, headers = {}) {
  const tok = hex(rand(32)), ttl = remember ? REMEMBER_DAYS * 86400 : IDLE_MIN * 60;
  await env.DB.put("sess:" + await sha(tok), JSON.stringify({ gen: user.gen, remember: !!remember, created: Date.now(), last: Date.now(), ip: clientIp(req), c: country(req) }), { expirationTtl: ttl });
  headers["Set-Cookie"] = COOKIE + "=" + tok + "; Path=/; Secure; HttpOnly; SameSite=Strict" + (remember ? "; Max-Age=" + ttl : "");
  return headers;
}
const clearCookie = () => ({ "Set-Cookie": COOKIE + "=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0" });
async function getSession(req, env) {
  const tok = getCookie(req, COOKIE); if (!/^[0-9a-f]{64}$/.test(tok)) return null;
  const id = "sess:" + await sha(tok), s = await env.DB.get(id, "json"); if (!s) return null;
  const user = await getUser(env); if (!user || s.gen !== user.gen) return null;
  if (await env.DB.get("lock")) return null;
  const ttl = s.remember ? REMEMBER_DAYS * 86400 : IDLE_MIN * 60;
  if (Date.now() - s.last > ttl * 1000) { await env.DB.delete(id); return null; }
  if (Date.now() - s.last > 60000) { s.last = Date.now(); await env.DB.put(id, JSON.stringify(s), { expirationTtl: ttl }); }
  return { id, s, user };
}

// ---------------------------------------------------------------- GitHub
function b64(bytes) { let s = ""; for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)); return btoa(s); }
const unb64 = s => Uint8Array.from(atob(String(s).replace(/\s/g, "")), c => c.charCodeAt(0));
async function gh(env, path, opts = {}) {
  if (!env.GITHUB_TOKEN) { const e = new Error("no token"); e.status = 0; e.msg = "Manca GITHUB_TOKEN nelle impostazioni del Worker."; throw e; }
  const r = await fetch("https://api.github.com/repos/" + repo(env) + path, {
    ...opts,
    headers: { Accept: "application/vnd.github+json", Authorization: "Bearer " + env.GITHUB_TOKEN, "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "kairos-store-panel", ...(opts.body ? { "Content-Type": "application/json" } : {}) },
  });
  if (!r.ok) { const e = new Error("GitHub " + r.status); e.status = r.status; throw e; }
  return r.status === 204 ? null : r.json();
}
function ghError(e) {
  if (e.msg) return fail(e.msg, 500);
  if (e.status === 401) return fail("Il token GitHub nel Worker non è valido o è scaduto: creane uno nuovo e aggiorna GITHUB_TOKEN.", 502);
  if (e.status === 403) return fail("Il token GitHub non ha il permesso di scrivere (Contents → Read and write).", 502);
  if (e.status === 404) return fail("Repository non trovato, oppure il token non è abilitato per kairos-store.", 502);
  if (e.status === 409 || e.status === 422) return fail("La lista è stata modificata altrove nel frattempo. Ricarica e riprova.", 409);
  return fail("GitHub non risponde (" + (e.status || "rete") + "). Riprova tra poco.", 502);
}

// ---------------------------------------------------------------- controllo dei dati salvati
const str = (v, max) => String(v == null ? "" : v).replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, max);
function cleanData(d) {
  if (!d || typeof d !== "object" || !Array.isArray(d.apps)) throw new Error("Dati non validi.");
  if (d.apps.length > 60) throw new Error("Massimo 60 app.");
  const s = d.store || {};
  const out = {
    store: {
      name: str(s.name, 40) || "KAIROS STORE",
      taglines: (Array.isArray(s.taglines) ? s.taglines : []).map(t => str(t, 120)).filter(Boolean).slice(0, 10),
      contact: str(s.contact, 80),
    },
    apps: [],
  };
  d.apps.forEach((a, i) => {
    a = a || {};
    const o = {
      name: str(a.name, 30), tag: str(a.tag, 14), description: str(a.description, 90), url: str(a.url, 500),
      code: str(a.code, 12), icon: str(a.icon, 120), glow: str(a.glow, 7), pkg: str(a.pkg, 100),
      light: a.light === true, hidden: a.hidden === true,
    };
    const n = "App " + (i + 1) + (o.name ? " (" + o.name + ")" : "");
    if (!o.name) throw new Error(n + ": manca il nome.");
    if (o.url && !/^https?:\/\/[^\s"'<>\\]+\.[^\s"'<>\\]+$/i.test(o.url)) throw new Error(n + ": il link all'APK non è valido.");
    if (!o.url && !o.hidden) throw new Error(n + ": manca il link all'APK (oppure nascondila).");
    if (o.icon && !/^icons\/[A-Za-z0-9._-]+\.(webp|png|jpe?g|svg)$/.test(o.icon)) throw new Error(n + ": icona non valida.");
    if (!/^#[0-9a-fA-F]{6}$/.test(o.glow)) o.glow = "#22d3ee";
    if (!/^[A-Za-z0-9._]*$/.test(o.pkg)) throw new Error(n + ": nome pacchetto non valido.");
    out.apps.push(o);
  });
  return out;
}

// ---------------------------------------------------------------- richieste
async function handle(req, env, ctx) {
  const url = new URL(req.url), p = url.pathname;
  if (req.method === "GET" && (p === "/" || p === "/index.html")) {
    return new Response(PAGE, { headers: secHeaders(env, { "Content-Type": "text/html; charset=utf-8" }) });
  }
  if (req.method === "GET" && p === "/app.js") return new Response(APPJS, { headers: secHeaders(env, { "Content-Type": "application/javascript; charset=utf-8" }) });
  if (p === "/robots.txt") return new Response("User-agent: *\nDisallow: /\n", { headers: { "Content-Type": "text/plain" } });
  if (!p.startsWith("/api/")) return new Response("Non trovato", { status: 404, headers: secHeaders(env) });
  if (!env.DB) return fail("Manca il collegamento KV chiamato DB nelle impostazioni del Worker.", 500);
  if (!countryAllowed(req, env)) { await log(env, req, "paese non consentito " + country(req), false); return fail("Accesso non consentito da questo paese.", 403); }

  // protezione CSRF: solo richieste dalla pagina stessa
  if (req.method !== "GET") {
    const origin = req.headers.get("origin");
    if (req.headers.get("x-kairos") !== "1" || (origin && origin !== url.origin)) return fail("Richiesta non consentita.", 403);
    if (!(req.headers.get("content-type") || "").startsWith("application/json")) return fail("Richiesta non valida.", 415);
    if (+(req.headers.get("content-length") || 0) > 1500000) return fail("Richiesta troppo grande.", 413);
  }
  let body = {};
  if (req.method !== "GET") {
    const t = await req.text(); if (t.length > 1500000) return fail("Richiesta troppo grande.", 413);
    try { body = t ? JSON.parse(t) : {}; } catch (e) { return fail("Richiesta non valida.", 400); }
    if (!body || typeof body !== "object") body = {};
  }
  const route = req.method + " " + p;
  const user = await getUser(env);
  const locked = await env.DB.get("lock");

  // ---------- senza accesso
  if (route === "GET /api/state") {
    const sess = await getSession(req, env);
    return json({
      version: VERSION, setup: !!user, locked: !!locked, turnstile: env.TURNSTILE_SITEKEY || "", storeUrl: storeUrl(env),
      logged: !!sess, enrolled: !!(user && user.totp && user.totp.enabled), username: sess ? user.username : "",
      setupKey: !!env.SETUP_KEY,
    });
  }
  if (route === "POST /api/setup") {
    if (user) return fail("Il pannello è già configurato.", 403);
    if (!env.SETUP_KEY) return fail("Manca SETUP_KEY nelle impostazioni del Worker.", 500);
    if (await isBlocked(env, req)) return fail(BLOCKED_MSG, 429);
    if (!await turnstileOk(env, req, body.ts)) return fail("Verifica anti-robot non superata. Riprova.", 400);
    if (!eq(String(body.key || ""), env.SETUP_KEY)) { await addFail(env, ctx, req, "configurazione: SETUP_KEY errata"); return fail("SETUP_KEY errata.", 403); }
    const username = str(body.username, 32);
    if (!/^[A-Za-z0-9._-]{3,32}$/.test(username)) return fail("Username: da 3 a 32 caratteri (lettere, numeri, punto, trattino).");
    const pp = pwProblem(body.password); if (pp) return fail(pp);
    const u = { username, pass: await makePass(body.password), totp: { enabled: false }, recovery: [], gen: 1, created: Date.now() };
    await env.DB.put("user", JSON.stringify(u));
    await clearFails(env, req); await log(env, req, "pannello configurato");
    notify(env, ctx, "✅ Pannello configurato. Utente: " + username + "\nIP: " + clientIp(req) + " (" + country(req) + ")");
    return json({ ok: true }, 200, await newSession(env, req, u, false));
  }
  if (route === "POST /api/login") {
    if (!user) return fail("Il pannello non è ancora configurato.", 409);
    if (locked) return fail("Pannello bloccato con il blocco di emergenza.", 423, { locked: true });
    if (await isBlocked(env, req)) return fail(BLOCKED_MSG, 429);
    if (!await turnstileOk(env, req, body.ts)) return fail("Verifica anti-robot non superata. Riprova.", 400);
    const okUser = eq(String(body.username || "").trim().toLowerCase(), user.username.toLowerCase());
    const okPass = await checkPass(okUser ? user.pass : DUMMY, body.password);
    if (!okUser || !okPass) { await addFail(env, ctx, req, "accesso: credenziali errate"); return fail("Username o password errati.", 401); }
    if (user.totp && user.totp.enabled) {
      const ticket = hex(rand(24));
      await env.DB.put("tk:" + await sha(ticket), JSON.stringify({ remember: body.remember === true, n: 0, ip: clientIp(req) }), { expirationTtl: 300 });
      return json({ step: "2fa", ticket });
    }
    // verifica in due passaggi non ancora attivata: serve anche la SETUP_KEY
    if (!env.SETUP_KEY || !eq(String(body.key || ""), env.SETUP_KEY)) {
      if (body.key) { await addFail(env, ctx, req, "accesso: SETUP_KEY errata"); return fail("SETUP_KEY errata.", 403, { needKey: true }); }
      return fail("Prima attiva la verifica in due passaggi: inserisci anche la SETUP_KEY.", 403, { needKey: true });
    }
    await clearFails(env, req); await log(env, req, "accesso (verifica in due passaggi da attivare)");
    return json({ ok: true, enroll: true }, 200, await newSession(env, req, user, false));
  }
  if (route === "POST /api/login/2fa") {
    if (!user || !user.totp || !user.totp.enabled) return fail("Richiesta non valida.", 400);
    if (locked) return fail("Pannello bloccato con il blocco di emergenza.", 423, { locked: true });
    if (await isBlocked(env, req)) return fail(BLOCKED_MSG, 429);
    const key = "tk:" + await sha(String(body.ticket || "")), tk = await env.DB.get(key, "json");
    if (!tk || tk.ip !== clientIp(req)) return fail("Accesso scaduto: inserisci di nuovo username e password.", 401, { restart: true });
    const how = await secondFactor(env, ctx, req, user, body.code);
    if (!how) {
      tk.n++; if (tk.n >= 5) await env.DB.delete(key); else await env.DB.put(key, JSON.stringify(tk), { expirationTtl: 300 });
      await addFail(env, ctx, req, "accesso: codice di verifica errato");
      return fail(tk.n >= 5 ? "Troppi codici errati: ricomincia l'accesso." : "Codice errato.", 401, tk.n >= 5 ? { restart: true } : {});
    }
    await env.DB.delete(key); await clearFails(env, req);
    await log(env, req, how === "recovery" ? "accesso con codice di recupero" : "accesso");
    notify(env, ctx, "🔓 Accesso al pannello" + (tk.remember ? " (dispositivo ricordato)" : "") + "\nIP: " + clientIp(req) + " (" + country(req) + ")");
    return json({ ok: true, recoveryLeft: (user.recovery || []).length }, 200, await newSession(env, req, user, tk.remember));
  }
  if (route === "POST /api/unlock") {
    if (!user) return fail("Richiesta non valida.", 400);
    if (!locked) return fail("Il pannello non è bloccato.", 400);
    if (!env.SETUP_KEY) return fail("Manca SETUP_KEY nelle impostazioni del Worker.", 500);
    if (await isBlocked(env, req)) return fail(BLOCKED_MSG, 429);
    if (!await turnstileOk(env, req, body.ts)) return fail("Verifica anti-robot non superata. Riprova.", 400);
    const okKey = eq(String(body.key || ""), env.SETUP_KEY);
    const okUser = eq(String(body.username || "").trim().toLowerCase(), user.username.toLowerCase());
    const okPass = await checkPass(user.pass, body.password);
    const okCode = okKey && okUser && okPass && user.totp && user.totp.enabled ? !!(await secondFactor(env, ctx, req, user, body.code)) : false;
    if (!okKey || !okUser || !okPass || (user.totp && user.totp.enabled && !okCode)) { await addFail(env, ctx, req, "sblocco: dati errati"); return fail("Dati errati.", 401); }
    await env.DB.delete("lock"); await clearFails(env, req); await log(env, req, "blocco di emergenza disattivato");
    notify(env, ctx, "🟢 Blocco di emergenza disattivato\nIP: " + clientIp(req) + " (" + country(req) + ")");
    return json({ ok: true }, 200, await newSession(env, req, user, false));
  }

  // ---------- con accesso
  const sess = await getSession(req, env);
  if (!sess) return fail("Sessione scaduta: accedi di nuovo.", 401, { relogin: true });
  const u = sess.user;
  if (route === "POST /api/logout") { await env.DB.delete(sess.id); return json({ ok: true }, 200, clearCookie()); }

  if (route === "POST /api/2fa/start") {
    if (u.totp.enabled) return fail("La verifica in due passaggi è già attiva.", 400);
    u.totp.pending = b32enc(rand(20)); await env.DB.put("user", JSON.stringify(u));
    const label = encodeURIComponent("Kairos Store:" + u.username);
    return json({ secret: u.totp.pending, uri: "otpauth://totp/" + label + "?secret=" + u.totp.pending + "&issuer=" + encodeURIComponent("Kairos Store") + "&digits=6&period=30" });
  }
  if (route === "POST /api/2fa/confirm") {
    if (u.totp.enabled) return fail("La verifica in due passaggi è già attiva.", 400);
    if (!u.totp.pending) return fail("Ricomincia l'attivazione.", 400);
    if (await isBlocked(env, req)) return fail(BLOCKED_MSG, 429);
    const st = await totpMatch(u.totp.pending, body.code, 0);
    if (!st) { await addFail(env, ctx, req, "attivazione verifica: codice errato"); return fail("Codice errato. Controlla che l'ora del telefono sia automatica.", 400); }
    const rec = await newRecovery();
    u.totp = { enabled: true, secret: u.totp.pending, last: st }; u.recovery = rec.hashes; u.gen++;
    await env.DB.put("user", JSON.stringify(u)); await clearFails(env, req);
    await log(env, req, "verifica in due passaggi attivata");
    notify(env, ctx, "🔐 Verifica in due passaggi attivata\nIP: " + clientIp(req) + " (" + country(req) + ")");
    await env.DB.delete(sess.id);
    return json({ ok: true, recovery: rec.plain }, 200, await newSession(env, req, u, sess.s.remember));
  }
  if (!u.totp.enabled) return fail("Attiva prima la verifica in due passaggi.", 403, { enroll: true });

  if (route === "GET /api/data") {
    try {
      let data, sha_ = "";
      try {
        const j = await gh(env, "/contents/docs/apps.json?ref=main"); sha_ = j.sha;
        data = JSON.parse(new TextDecoder().decode(unb64(j.content)));
      } catch (e) {
        if (e.status !== 404) throw e;
        data = { store: { name: "KAIROS STORE", taglines: ["Le tue app, in un solo posto."], contact: "" }, apps: [] };
      }
      let icons = [];
      try { icons = (await gh(env, "/contents/docs/icons?ref=main")).filter(f => f.type === "file" && /\.(webp|png|jpe?g|svg)$/i.test(f.name)).map(f => "icons/" + f.name); } catch (e) {}
      return json({ data, sha: sha_, icons, recoveryLeft: (u.recovery || []).length });
    } catch (e) { return ghError(e); }
  }
  if (route === "PUT /api/data") {
    let clean; try { clean = cleanData(body.data); } catch (e) { return fail(e.message, 400); }
    const put = { message: "Aggiorna app dal pannello", content: b64(te.encode(JSON.stringify(clean, null, 2) + "\n")), branch: "main" };
    if (body.sha) put.sha = String(body.sha);
    try {
      const r = await gh(env, "/contents/docs/apps.json", { method: "PUT", body: JSON.stringify(put) });
      await log(env, req, "lista app salvata (" + clean.apps.length + " app)");
      notify(env, ctx, "💾 Lista app aggiornata (" + clean.apps.filter(a => !a.hidden).length + " visibili)\nIP: " + clientIp(req));
      return json({ ok: true, sha: r.content.sha, data: clean });
    } catch (e) { return ghError(e); }
  }
  if (route === "POST /api/icon") {
    const ext = body.type === "png" ? "png" : body.type === "webp" ? "webp" : "";
    if (!ext) return fail("Formato icona non valido.");
    let bytes; try { bytes = unb64(body.data || ""); } catch (e) { return fail("Immagine non valida."); }
    if (!bytes.length || bytes.length > 400000) return fail("Immagine troppo grande.");
    const isPng = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
    const isWebp = String.fromCharCode(...bytes.slice(0, 4)) === "RIFF" && String.fromCharCode(...bytes.slice(8, 12)) === "WEBP";
    if ((ext === "png" && !isPng) || (ext === "webp" && !isWebp)) return fail("Immagine non valida.");
    const slug = str(body.name, 30).toLowerCase().normalize("NFD").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "app";
    const path = "icons/" + slug + "-" + Date.now().toString(36) + "." + ext;
    try {
      await gh(env, "/contents/docs/" + path, { method: "PUT", body: JSON.stringify({ message: "Icona " + slug + " dal pannello", content: b64(bytes), branch: "main" }) });
      await log(env, req, "icona caricata " + path);
      return json({ ok: true, path });
    } catch (e) { return ghError(e); }
  }
  if (route === "GET /api/security") {
    let sessions = 0;
    try { const l = await env.DB.list({ prefix: "sess:" }); for (const k of l.keys) { const s = await env.DB.get(k.name, "json"); if (s && s.gen === u.gen) sessions++; } } catch (e) {}
    return json({ log: ((await env.DB.get("log", "json")) || []).slice(0, 100), recoveryLeft: (u.recovery || []).length, sessions,
      remember: !!sess.s.remember, telegram: !!(env.TELEGRAM_TOKEN && env.TELEGRAM_CHAT), turnstile: !!env.TURNSTILE_SECRET,
      countries: String(env.ALLOWED_COUNTRIES || ""), idleMin: IDLE_MIN, rememberDays: REMEMBER_DAYS });
  }
  if (route === "POST /api/password") {
    if (await isBlocked(env, req)) return fail(BLOCKED_MSG, 429);
    if (!await checkPass(u.pass, body.old)) { await addFail(env, ctx, req, "cambio password: password attuale errata"); return fail("Password attuale errata.", 401); }
    if (!await secondFactor(env, ctx, req, u, body.code)) { await addFail(env, ctx, req, "cambio password: codice errato"); return fail("Codice di verifica errato.", 401); }
    const pp = pwProblem(body.password); if (pp) return fail(pp);
    u.pass = await makePass(body.password); u.gen++; await env.DB.put("user", JSON.stringify(u));
    await log(env, req, "password cambiata (altri dispositivi disconnessi)");
    notify(env, ctx, "🔁 Password cambiata\nIP: " + clientIp(req) + " (" + country(req) + ")");
    await env.DB.delete(sess.id);
    return json({ ok: true }, 200, await newSession(env, req, u, sess.s.remember));
  }
  if (route === "POST /api/recovery") {
    if (await isBlocked(env, req)) return fail(BLOCKED_MSG, 429);
    if (!await checkPass(u.pass, body.password)) { await addFail(env, ctx, req, "nuovi codici: password errata"); return fail("Password errata.", 401); }
    if (!await secondFactor(env, ctx, req, u, body.code)) { await addFail(env, ctx, req, "nuovi codici: codice errato"); return fail("Codice di verifica errato.", 401); }
    const rec = await newRecovery(); u.recovery = rec.hashes; await env.DB.put("user", JSON.stringify(u));
    await log(env, req, "nuovi codici di recupero");
    notify(env, ctx, "🔑 Generati nuovi codici di recupero\nIP: " + clientIp(req));
    return json({ ok: true, recovery: rec.plain });
  }
  if (route === "POST /api/logout-all") {
    u.gen++; await env.DB.put("user", JSON.stringify(u)); await env.DB.delete(sess.id);
    await log(env, req, "uscita da tutti i dispositivi");
    notify(env, ctx, "🚪 Uscita da tutti i dispositivi\nIP: " + clientIp(req));
    return json({ ok: true }, 200, clearCookie());
  }
  if (route === "POST /api/lock") {
    await env.DB.put("lock", JSON.stringify({ t: Date.now(), ip: clientIp(req) }));
    u.gen++; await env.DB.put("user", JSON.stringify(u));
    await log(env, req, "BLOCCO DI EMERGENZA attivato");
    notify(env, ctx, "⛔ BLOCCO DI EMERGENZA attivato: tutti disconnessi, accesso sospeso.\nPer sbloccare servono SETUP_KEY, password e codice.\nIP: " + clientIp(req) + " (" + country(req) + ")");
    return json({ ok: true }, 200, clearCookie());
  }
  return fail("Non trovato.", 404);
}
