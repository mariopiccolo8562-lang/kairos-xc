package com.kairos.xc

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * «Pulizia automatica» (19 novembre 2026). Il titolare: «dammi la possibilità su ogni applicazione di attivare con un bottone
 * lo svuotamento della RAM in automatico, con opzione di tot giorni, così da tenere sempre pulita la RAM del dispositivo».
 * Voce del pannello «clean» = ogni quanti giorni (1, 3, 7, 15, 30); spenta di serie (nessun valore = mai).
 * Quando è il momento, al primo battito del pannello in cui il cliente NON sta guardando:
 *  - chiude i processi in sottofondo delle altre app installate (come «Libera memoria» del Kairos Store; permesso
 *    KILL_BACKGROUND_PROCESSES). Le app di sistema non si toccano; quella in primo piano e i servizi attivi Android li lascia;
 *  - svuota la cache delle immagini (locandine e loghi: si riscaricano quando servono) e quella in memoria.
 * Le liste dei canali, i preferiti e le impostazioni NON si toccano: l'app non ricarica niente.
 * Il giorno dell'ultima pulizia sta in un file suo («autoclean»), separato dal resto: «svuota cache» non lo azzera.
 */
object AutoClean {
    const val DAY_MS = 24 * 3600_000L
    private const val PREFS = "autoclean"
    @Volatile private var running = false

    /** voce del pannello → giorni (0 = spenta); valori sconosciuti = spenta */
    fun days(v: String?): Int = when (v?.trim()) { "1" -> 1; "3" -> 3; "7" -> 7; "15" -> 15; "30" -> 30; else -> 0 }

    /** è ora? [last] = ultima pulizia (0 = mai: la prima volta si pulisce subito); un orologio tornato indietro non blocca */
    fun due(now: Long, last: Long, days: Int): Boolean = days > 0 && (last <= 0L || now < last || now - last >= days * DAY_MS)

    /** da chiamare a ogni battito del pannello (fuori dal filo dell'interfaccia) */
    fun tick(ctx: Context, value: String?, watching: Boolean) {
        val d = days(value)
        if (d == 0 || watching || running) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!due(now, p.getLong("at", 0L), d)) return
        running = true
        try {
            p.edit().putLong("at", now).apply() // segnata subito: se qualcosa va storto non si riprova a ogni battito
            killBackground(ctx)
            try { Img.glide().clearDiskCache() } catch (_: Throwable) {}
            ui { try { Img.glide().clearMemory() } catch (_: Throwable) {} }
        } catch (_: Throwable) {
        } finally { running = false }
    }

    private fun killBackground(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val me = ctx.packageName
        val apps = try { ctx.packageManager.getInstalledApplications(0) } catch (_: Throwable) { return }
        for (ai in apps) {
            val n = ai.packageName ?: continue
            if (n == me) continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue
            try { am.killBackgroundProcesses(n) } catch (_: Throwable) {}
        }
    }
}
