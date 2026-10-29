package app.olauncher.features.assistant.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.olauncher.core.WaypointPrefs
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the API key encrypted at rest with AES-GCM under a key that lives in the Android Keystore and
 * can't be exported. The key is never logged. If the Keystore key is lost (e.g. restored on another
 * phone), the stored value simply can't be decrypted and the user re-enters it.
 */
class ApiKeyStore(private val prefs: WaypointPrefs) {

    fun get(): String? = runCatching {
        val sealed = prefs.apiKeySealed ?: return null
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()

    fun set(apiKey: String?) {
        if (apiKey.isNullOrBlank()) { prefs.apiKeySealed = null; return }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        prefs.apiKeySealed = Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** "••••abcd", or null when no key is stored. */
    fun masked(): String? = get()?.let { k -> "••••" + k.takeLast(4) }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "waypoint_assistant_api_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
