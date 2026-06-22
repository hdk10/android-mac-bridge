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
}
