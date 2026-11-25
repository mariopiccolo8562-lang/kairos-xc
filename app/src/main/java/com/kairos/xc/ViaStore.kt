package com.kairos.xc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * Aggiornamento installato dal Kairos Store (25 novembre 2026, il titolare: «possibile disattivare la procedura app sconosciute
 * per rendere l'installazione più veloce e con meno passaggi?»). Il permesso «Installa app sconosciute» non si può togliere
 * (regola di Android), ma Android lo chiede a ogni app che installa qualcosa: prima ogni app Kairos lo chiedeva per sé al
 * primo aggiornamento. Ora, se l'app non ce l'ha e sul dispositivo c'è un Kairos Store che sa farlo, l'APK già scaricato e
 * verificato passa allo Store, che il permesso ce l'ha già: il cliente lo accende una volta sola, per lo Store. Lo Store
 * installa solo l'aggiornamento della stessa app, con la stessa firma e una versione più nuova; finito, riapre l'app.
 * Senza Store (o con uno Store vecchio) tutto come prima.
 */
object ViaStore {
    private const val PKG = "com.kairos.store"
    private const val ACTION = "com.kairos.store.UPDATE_APP"
    private const val TYPE = "application/vnd.android.package-archive"

    /** c'è un Kairos Store che installa gli aggiornamenti delle app */
    fun available(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 24 && try {
        ctx.packageManager.resolveActivity(Intent(ACTION).setPackage(PKG).setDataAndType(Uri.parse("content://" + ctx.packageName + ".files/apk/x.apk"), TYPE), 0) != null
    } catch (_: Throwable) { false }

    /** passa [apk] allo Store; false = non c'è o non si è aperto (si va avanti come prima) */
    fun start(a: Activity, apk: File): Boolean {
        if (!apk.exists() || !available(a)) return false
        return try {
            val uri = FileProvider.getUriForFile(a, a.packageName + ".files", apk)
            a.startActivity(Intent(ACTION).setPackage(PKG).setDataAndType(uri, TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Throwable) { false }
    }
}
