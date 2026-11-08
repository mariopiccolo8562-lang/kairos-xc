package com.kairos.xc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Ricerca unica su canali, film e serie (archivio locale, istantanea).
 * Con le funzioni intelligenti, sopra i risultati per nome: «✦ Cerca con l'assistente» per le frasi (criteri dall'assistente del
 * pannello, risultati dall'archivio con LIMIT), domande libere, «Cosa guardo stasera?» e la ricerca a voce (se il dispositivo
 * ha il riconoscimento vocale). Spente: la pagina è quella di sempre.
 */
class SearchActivity : BaseActivity() {
    private companion object { const val REQ_MIC = 812 }

    private lateinit var query: EditText
    private lateinit var empty: TextView
    private lateinit var mic: View
    private val adapter = HitAdapter()
    private var seq = 0
    private val searchR = Runnable { search() }
    /** ricerca in corso: con un testo nuovo quella vecchia si ferma (non restano tre letture dell'archivio in parallelo) */
    private var cancel: android.os.CancellationSignal? = null

    // funzioni intelligenti (decise all'apertura della pagina)
    private var smart = false
    private val picks = PickAdapter { onPick(it) }
    /** ultima richiesta all'assistente: testo e righe (stato «busy» = in corso) */
    private var aiText = ""
    private var aiRows: List<Pick> = emptyList()
    private var aiSeq = 0
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        query = findViewById(R.id.query)
        empty = findViewById(R.id.empty)
        mic = findViewById(R.id.mic)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        val rv = findViewById<RecyclerView>(R.id.results)
        rv.fast()
        smart = Smart.on()
        rv.adapter = if (smart) ConcatAdapter(picks, adapter) else adapter
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                App.main.removeCallbacks(searchR)
                App.main.postDelayed(searchR, 350)
            }
        })
        query.setOnEditorActionListener { _, _, _ -> if (adapter.items.isNotEmpty() || picks.items.isNotEmpty()) rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }; false }
        query.requestFocus()
        if (smart) {
            if (Ai.available()) query.hint = "Cerca un titolo, oppure scrivi una frase…"
            mic.setOnClickListener { voice() }
            // il pulsante del microfono solo se il dispositivo ha il riconoscimento vocale
            bg { val ok = try { SpeechRecognizer.isRecognitionAvailable(App.I) } catch (_: Throwable) { false }; ui { if (!isFinishing) mic.visibility = if (ok) View.VISIBLE else View.GONE } }
            assist()
            // da «Stasera» › «Chiedi all'assistente»
            if (intent.getBooleanExtra("ask", false)) App.main.post { ask() }
        }
    }

    override fun onPause() {
        super.onPause()
        stopVoice()
    }

    override fun onDestroy() {
        super.onDestroy()
        App.main.removeCallbacks(searchR)
        cancel?.cancel()
        aiSeq++
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }

    private fun search() {
        val t = query.text.toString()
        val my = ++seq
        val pls = K.activeIds()
        val kids = K.kids
        cancel?.cancel()
        val cs = android.os.CancellationSignal().also { cancel = it }
        if (smart) assist()
        bg {
            val hits = try { Db.search(t, pls, kids, cs) } catch (_: android.os.OperationCanceledException) { return@bg }
            ui {
                if (my != seq || isFinishing) return@ui
                adapter.items = hits
                adapter.notifyDataSetChanged()
                empty.visibility = if (hits.isEmpty() && picks.items.isEmpty()) View.VISIBLE else View.GONE
                empty.text = if (t.trim().length < 2) "Scrivi almeno 2 lettere" else "Nessun risultato per «${t.trim()}»"
            }
        }
    }

    /* ---------------- assistente (solo con le funzioni intelligenti) ---------------- */
    /** righe sopra i risultati per nome: proposte con il campo vuoto, «Cerca con l'assistente» per le frasi, risposte */
    private fun assist() {
        val t = query.text.toString().trim()
        val ai = Ai.available()
        val rows = when {
            t.isEmpty() -> listOfNotNull(
                Pick("Cosa guardo stasera?", "Da 3 a 5 proposte per te, con il perché", "", action = "tonight"),
                if (ai) Pick("Chiedi all'assistente", "Una domanda libera: «chi è l'attore di Rambo?», «film simili a quelli che guardo»", "", action = "ask") else null,
                if (ai) Pick("Cerca con una frase", "Es. «film d'azione anni 90», «la partita dell'Inter stasera», «qualcosa per bambini»", "", action = "hint") else null,
            )
            aiText.equals(t, true) && aiRows.isNotEmpty() -> aiRows
            ai && t.length >= 2 && AiFind.phrase(t) -> listOf(Pick(if (AiFind.question(t)) "Chiedi all'assistente" else "Cerca con l'assistente", "«$t» · premi OK", "", action = "go"))
            else -> emptyList()
        }
        if (rows == picks.items) return
        picks.items = rows
        picks.notifyDataSetChanged()
        if (rows.isNotEmpty()) empty.visibility = View.GONE
    }

    private fun onPick(p: Pick) {
        when (p.action) {
            "go" -> runAi(query.text.toString().trim())
            "tonight" -> open(SmartActivity::class.java) { putExtra("mode", "tonight") }
            "ask" -> ask()
            "hint" -> { query.requestFocus(); Ui.toast("Scrivi una frase e premi OK su «Cerca con l'assistente»") }
            "more" -> bg { val err = Panel.aiMore(); ui { Ui.toast(err ?: "Richiesta inviata: le domande in più arrivano appena vengono concesse") } }
            "" -> PickAdapter.open(this, p)
        }
    }

    /** domanda libera: si scrive nella finestra, poi la risposta in cima alla pagina */
    private fun ask() {
        if (!Ai.available()) { Ui.toast("L'assistente non è disponibile adesso"); return }
        Ui.input(this, "Chiedi all'assistente", "Es. a che ora c'è la F1?", false) { q ->
            if (q.length < 2) return@input
            setText(q)
            runAi(q)
        }
    }

    private fun setText(t: String) {
        query.setText(t)
        query.setSelection(t.length)
    }

    private fun runAi(q: String) {
        if (q.length < 2 || !Ai.available()) return
        val my = ++aiSeq
        aiText = q
        aiRows = listOf(Pick("Sto cercando…", "«$q»", "", action = "busy"))
        assist()
        net {
            val r = try { Ai.search(q) } catch (_: Exception) { Ai.Result(AiResp(0, null), "", emptyList(), "", emptyList()) }
            ui {
                if (my != aiSeq || isFinishing) return@ui
                aiRows = rowsOf(q, r)
                if (query.text.toString().trim().equals(q, true)) assist()
                findViewById<RecyclerView>(R.id.results).post { findViewById<RecyclerView>(R.id.results).findViewHolderForAdapterPosition(if (aiRows.size > 1) 1 else 0)?.itemView?.requestFocus() }
            }
        }
    }

    private fun rowsOf(q: String, r: Ai.Result): List<Pick> {
        if (!r.resp.ok) {
            val rows = arrayListOf(Pick(r.resp.msg, "Ecco i risultati per nome.", "", action = "err"))
            if (r.resp.limit) rows.add(Pick("Chiedi altre domande", "Le domande in più si usano dopo quelle del giorno", "", action = "more"))
            return rows
        }
        val head = listOf(r.label.ifEmpty { "«$q»" }) + r.crit
        val left = Ai.leftText(r.resp.left)
        val rows = arrayListOf(Pick(head.first(), (head.drop(1) + listOfNotNull(left.ifEmpty { null })).joinToString(" · "), "", action = "head"))
        if (r.answer.isNotEmpty()) rows.add(Pick("Risposta", r.answer, "", action = "answer"))
        rows.addAll(r.picks)
        if (r.picks.isEmpty()) rows.add(Pick(if (r.answer.isNotEmpty()) "Nella tua libreria non ho trovato altro" else "Non ho trovato niente di simile", "Ecco i risultati per nome.", "", action = "none"))
        return rows
    }

    /* ---------------- ricerca a voce (SpeechRecognizer di Android, in italiano) ---------------- */
    private fun voice() {
        if (listening) { stopVoice(); return }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // il permesso del microfono si chiede solo al primo uso; negato, lo si concede dalle impostazioni del dispositivo
            if (Store.micAsked) { Ui.toast("Per cercare a voce consenti il microfono nelle impostazioni del dispositivo"); return }
            Store.micAsked = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        listen()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen() else Ui.toast("Senza il microfono la ricerca a voce non funziona: puoi scrivere")
    }

    private fun listen() {
        try {
            val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onError(error: Int) {
                    voiceOff()
                    val m = when (error) {
                        1, 2, 4, 11 -> "Ricerca a voce non riuscita: controlla la connessione"
                        5 -> ""
                        6, 7 -> "Non ho capito: riprova"
                        8, 10 -> "Il riconoscimento vocale è occupato: riprova tra poco"
                        9 -> "Microfono non consentito: puoi attivarlo nelle impostazioni del dispositivo"
                        3 -> "Il microfono non risponde"
                        12, 13 -> "La ricerca a voce in italiano non è disponibile su questo dispositivo"
                        else -> "Ricerca a voce non riuscita"
                    }
                    if (m.isNotEmpty()) Ui.toast(m)
                }
                override fun onResults(results: Bundle?) {
                    voiceOff()
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty().take(200)
                    if (t.isEmpty()) { Ui.toast("Non ho capito: riprova"); return }
                    setText(t)
                    // come la Kairos Pro: il testo va nel campo e, se è una frase, parte la ricerca con l'assistente
                    if (Ai.available() && AiFind.phrase(t)) runAi(t)
                }
            })
            r.cancel()
            listening = true
            mic.isActivated = true
            query.hint = "Parla adesso…"
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "it-IT")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName))
        } catch (_: Throwable) {
            voiceOff()
            Ui.toast("La ricerca a voce non è disponibile adesso")
        }
    }

    private fun voiceOff() {
        listening = false
        mic.isActivated = false
        query.hint = if (Ai.available()) "Cerca un titolo, oppure scrivi una frase…" else "Cerca canali, film e serie"
    }

    private fun stopVoice() {
        if (!listening) return
        try { recognizer?.cancel() } catch (_: Throwable) {}
        voiceOff()
    }

    private fun openHit(h: Db.Hit) {
        when (h.table) {
            "live" -> {
                if (Updater.blocked(this)) return // aggiornamento obbligatorio: prima l'installazione
                Store.setLastLive(h.id, h.k)
                // funzioni intelligenti: il lettore imparato per il formato del canale (riga letta dall'archivio)
                if (!smart) { startActivity(Play.liveIntent(this, null, h.id, h.cat)); return }
                bg {
                    val r = try { Db.one("live", h.id) } catch (_: Exception) { null }
                    ui { if (!isFinishing) startActivity(Play.liveIntent(this, r, h.id, h.cat)) }
                }
            }
            else -> open(DetailActivity::class.java) { putExtra("table", h.table); putExtra("id", h.id); putExtra("k", h.k) }
        }
    }

    private inner class HitAdapter : RecyclerView.Adapter<HitAdapter.H>() {
        var items: List<Db.Hit> = emptyList()
        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.img)
            val name: TextView = v.findViewById(R.id.name)
            val kind: TextView = v.findViewById(R.id.kind)
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
            val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_hit, p, false))
            h.itemView.setOnClickListener { val i = h.bindingAdapterPosition; if (i in items.indices) openHit(items[i]) }
            return h
        }
        override fun onBindViewHolder(h: H, i: Int) {
            val x = items[i]
            h.name.text = x.name
            h.kind.text = when (x.table) { "live" -> "CANALE"; "vod" -> "FILM"; else -> "SERIE" }
            val s = Ui.dp(h.itemView.context, 36)
            Ui.img(h.img, x.img, s, s, if (x.table == "live") Img.LOGO else Img.POSTER)
        }

        // risultato uscito dallo schermo: il caricamento si annulla
        override fun onViewRecycled(h: H) { Img.clear(h.img) }
    }
}
