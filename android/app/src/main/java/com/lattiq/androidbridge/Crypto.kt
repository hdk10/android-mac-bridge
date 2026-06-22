package com.lattiq.androidbridge

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.SecretBox

/**
 * NaCl secretbox (XSalsa20-Poly1305) — wire-compatible with PyNaCl on the Mac.
 * Wire format: base64( nonce[24] || mac+ciphertext ).
 */
object Crypto {
    private val ls = LazySodiumAndroid(SodiumAndroid())

    fun encrypt(keyB64: String, plaintext: String): String {
        val key = Base64.decode(keyB64, Base64.DEFAULT)
        val nonce = ls.randomBytesBuf(SecretBox.NONCEBYTES)
        val msg = plaintext.toByteArray(Charsets.UTF_8)
        val cipher = ByteArray(SecretBox.MACBYTES + msg.size)
        check(ls.cryptoSecretBoxEasy(cipher, msg, msg.size.toLong(), nonce, key)) {
            "secretbox encrypt failed"
        }
        return Base64.encodeToString(nonce + cipher, Base64.NO_WRAP)
    }

    /** Decrypt a base64(nonce||cipher) blob → plaintext, or null. For Mac→phone control messages. */
    fun decrypt(keyB64: String, blobB64: String): String? {
        return try {
            val key = Base64.decode(keyB64, Base64.DEFAULT)
            val raw = Base64.decode(blobB64, Base64.DEFAULT)
            val nonce = raw.copyOfRange(0, SecretBox.NONCEBYTES)
            val cipher = raw.copyOfRange(SecretBox.NONCEBYTES, raw.size)
            val msg = ByteArray(cipher.size - SecretBox.MACBYTES)
            if (!ls.cryptoSecretBoxOpenEasy(msg, cipher, cipher.size.toLong(), nonce, key)) return null
            String(msg, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
