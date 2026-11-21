package com.kairos.xc

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cassaforte dei dati delle linee sul dispositivo (21 novembre 2026). Il titolare: «proteggi i miei DNS… voglio vederli solo
 * io». DNS, utente e password salvati dall'app (stato del pannello, server delle playlist, «Continua a guardare») si
 * scrivono cifrati con AES-256-GCM: chi copia i file dell'app (backup, dispositivo con permessi di root) non li legge.
 *
 * La chiave dei dati (32 byte a caso) è chiusa con una chiave del portachiavi di Android (Android 6 e successivi, sul chip
 * di sicurezza dove c'è). Sulle chiavette con Android 5.1 il portachiavi non ha AES: la chiave si ricava dall'identificativo
 * del dispositivo e da un sale a caso (più debole, ma i file non sono leggibili a occhio).
 *
 * Se la chiave si perde (portachiavi azzerato da un aggiornamento di sistema) i valori cifrati non si aprono più: l'app li
 * tratta come vuoti e li riprende dal pannello al primo collegamento, come dopo «Svuota cache». Mai il codice della TV.
 *
 * Limite che non dipende dall'app: mentre il cliente guarda, l'indirizzo del server passa in rete.
 */
object Vault {
    private const val PREFIX = "kv1:"
    private const val ALIAS = "kairos_vault_v1"
    private const val PREFS = "vault"
    private const val DEK = "dek"
    private const val MODE = "mode"
    private const val SALT = "salt"
    private const val IV = 12
    private const val TAG = 128
    private val lock = Any()
    @Volatile private var ctx: Context? = null
    @Volatile private var key: SecretKey? = null
    @Volatile private var broken = false

    fun init(c: Context) { ctx = c.applicationContext }

    fun isSealed(v: String?): Boolean = v != null && v.startsWith(PREFIX)

    /** testo cifrato, oppure null se la cassaforte non è disponibile (chi chiama salva allora il testo com'è) */
    fun seal(plain: String): String? {
        if (plain.isEmpty()) return plain
        val k = dataKey() ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            val iv = c.iv ?: return null
            PREFIX + Base64.encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }

    /** valore in chiaro: quello vecchio non cifrato passa com'è; null se è cifrato e non si apre */
    fun open(v: String?): String? {
        if (v == null || !v.startsWith(PREFIX)) return v
        val k = dataKey() ?: return null
        return try {
            val raw = Base64.decode(v.substring(PREFIX.length), Base64.NO_WRAP)
            if (raw.size < IV + TAG / 8) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG, raw, 0, IV))
            String(c.doFinal(raw, IV, raw.size - IV), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    /** impronta (SHA-256, 32 caratteri) per confrontare senza salvare il testo: firme delle liste, nomi delle chiavi */
    fun hash(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (i in 0 until 16) sb.append(String.format("%02x", d[i].toInt() and 0xff))
        return sb.toString()
    }

    /** stessa firma: quella salvata può essere l'impronta (versioni nuove) o il testo (versioni vecchie) */
    fun sameSig(stored: String, sig: String): Boolean = stored.isNotEmpty() && (stored == hash(sig) || stored == sig)

    private fun dataKey(): SecretKey? {
        key?.let { return it }
        if (broken) return null
        synchronized(lock) {
            key?.let { return it }
            val c = ctx ?: return null
            return try {
                val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val wrapped = p.getString(DEK, null)
                val mode = p.getString(MODE, null)
                var k: SecretKey? = null
                if (wrapped != null && mode != null) {
                    val w = if (mode == "ks") keystoreKey(false) else localKey(c, p)
                    if (w != null) k = unwrap(w, wrapped)
                }
                if (k == null) {
                    // prima volta (o chiave persa): una nuova; i valori vecchi cifrati non si aprono e tornano dal pannello
                    val raw = ByteArray(32).also(SecureRandom()::nextBytes)
                    var m = "local"
                    var w: SecretKey? = null
                    if (Build.VERSION.SDK_INT >= 23) w = try { keystoreKey(true) } catch (_: Exception) { null }?.also { m = "ks" }
                    if (w == null) w = localKey(c, p)
                    val env = wrap(w, raw)
                    if (!p.edit().putString(DEK, env).putString(MODE, m).commit()) throw IllegalStateException("vault")
                    k = SecretKeySpec(raw, "AES")
                    raw.fill(0)
                }
                key = k
                k
            } catch (_: Throwable) { broken = true; null }
        }
    }

    private fun wrap(w: SecretKey, raw: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, w)
        return Base64.encodeToString(c.iv + c.doFinal(raw), Base64.NO_WRAP)
    }

    private fun unwrap(w: SecretKey, env: String): SecretKey? = try {
        val b = Base64.decode(env, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, w, GCMParameterSpec(TAG, b, 0, IV))
        val raw = c.doFinal(b, IV, b.size - IV)
        if (raw.size == 32) SecretKeySpec(raw, "AES").also { raw.fill(0) } else null
    } catch (_: Exception) { null }

    private fun keystoreKey(create: Boolean): SecretKey? {
        if (Build.VERSION.SDK_INT < 23) return null
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    /** Android 5.1 o portachiavi guasto: chiave dall'identificativo del dispositivo e da un sale a caso (PBKDF2, 2 000 giri: pochi millisecondi anche sulle chiavette vecchie) */
    private fun localKey(c: Context, p: android.content.SharedPreferences): SecretKey {
        var salt = try { Base64.decode(p.getString(SALT, "") ?: "", Base64.NO_WRAP) } catch (_: Exception) { ByteArray(0) }
        if (salt.size != 16) {
            salt = ByteArray(16).also(SecureRandom()::nextBytes)
            p.edit().putString(SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).commit()
        }
        val aid = try { Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID).orEmpty() } catch (_: Exception) { "" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("$aid:${c.packageName}".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(1_999) { u = mac.doFinal(u); for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte() }
        return SecretKeySpec(out, "AES")
    }
}
