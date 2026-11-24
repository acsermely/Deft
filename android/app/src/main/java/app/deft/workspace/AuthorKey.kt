package app.deft.workspace

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Author ed25519 key. The private PKCS8 bytes are encrypted with an Android Keystore AES key.
 * Signing stays in the host.
 */
object AuthorKey {
    private const val ALIAS = "deft-author-wrap"
    private const val IV_LEN = 12

    fun publicKey(context: Context): String {
        ensure(context)
        return FilePub(context).readText()
    }

    fun sign(context: Context, payload: ByteArray): ByteArray {
        val privateBytes = decrypt(context, readCipher(context))
        val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(key)
        sig.update(payload)
        return sig.sign()
    }

    private fun ensure(context: Context) {
        val pub = FilePub(context)
        val secret = FileSecret(context)
        if (pub.isFile && secret.isFile) return
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        pub.parentFile?.mkdirs()
        pub.writeText(Base64.getEncoder().encodeToString(pair.public.encoded))
        secret.writeBytes(encrypt(pair.private.encoded))
    }

    private fun FilePub(context: Context) = java.io.File(context.filesDir, "deft/author.pub")

    private fun FileSecret(context: Context) = java.io.File(context.filesDir, "deft/author.key")

    private fun readCipher(context: Context): ByteArray {
        ensure(context)
        return FileSecret(context).readBytes()
    }

    private fun wrapKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return iv + body
    }

    private fun decrypt(context: Context, packed: ByteArray): ByteArray {
        val iv = packed.copyOfRange(0, IV_LEN)
        val body = packed.copyOfRange(IV_LEN, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }
}
