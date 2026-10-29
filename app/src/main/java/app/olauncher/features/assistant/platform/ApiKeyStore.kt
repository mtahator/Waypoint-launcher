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
class ApiKeyStore(private val prefs: WaypointPrefs, private val slot: Slot = Slot.CHAT) {

    /** Which secret: the chat/image API key, or the Brave Search key. */
    enum class Slot(val alias: String) { CHAT("waypoint_assistant_api_key"), BRAVE("waypoint_brave_search_key") }

    private var sealed: String?
        get() = if (slot == Slot.CHAT) prefs.apiKeySealed else prefs.braveKeySealed
        set(value) { if (slot == Slot.CHAT) prefs.apiKeySealed = value else prefs.braveKeySealed = value }


    fun get(): String? = runCatching {
        val blob = sealed ?: return null
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()

    fun set(apiKey: String?) {
        if (apiKey.isNullOrBlank()) { sealed = null; return }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        sealed = Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** "••••abcd", or null when no key is stored. */
    fun masked(): String? = get()?.let { k -> "••••" + k.takeLast(4) }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(slot.alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(slot.alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
