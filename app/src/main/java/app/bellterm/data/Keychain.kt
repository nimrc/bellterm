package app.bellterm.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.bellterm.core.Native
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** An SSH key the app can sign in with, and its certificate if it has one. */
data class Identity(
    val id: String,
    val name: String,
    val algorithm: String,
    val fingerprint: String,
    val publicKey: String,
    val certificate: String? = null,
    val created: Long = System.currentTimeMillis(),
) {
    val isDevice: Boolean get() = id == Keychain.DEVICE

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("algorithm", algorithm)
        .put("fingerprint", fingerprint).put("public", publicKey)
        .put("certificate", certificate).put("created", created)

    companion object {
        fun fromJson(o: JSONObject) = Identity(
            id = o.getString("id"),
            name = o.getString("name"),
            algorithm = o.optString("algorithm"),
            fingerprint = o.optString("fingerprint"),
            publicKey = o.optString("public"),
            certificate = o.optString("certificate").takeUnless { o.isNull("certificate") || it.isEmpty() },
            created = o.optLong("created"),
        )
    }
}

/** A certificate's details, as the Rust core reads them. */
data class CertificateInfo(
    val keyId: String,
    val principals: List<String>,
    val caFingerprint: String,
    val validAfter: Long,
    val validBefore: Long,
    val matches: Boolean,
    val current: Boolean,
) {
    companion object {
        fun parse(json: String): CertificateInfo {
            val o = JSONObject(json)
            val principals = o.getJSONArray("principals")
            return CertificateInfo(
                keyId = o.getString("key_id"),
                principals = List(principals.length()) { principals.getString(it) },
                caFingerprint = o.getString("ca_fingerprint"),
                validAfter = o.getLong("valid_after"),
                validBefore = o.getLong("valid_before"),
                matches = o.getBoolean("matches"),
                current = o.getBoolean("current"),
            )
        }
    }
}

/**
 * SSH identities and saved node passwords. Private keys and passwords are
 * encrypted at rest with an AES-GCM key held by the Android Keystore, which
 * never leaves it; the device key is generated on first use and never
 * exported. Metadata and certificates (public data) live in preferences.
 */
class Keychain(context: Context) {
    private val prefs = context.getSharedPreferences("keychain", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "keys").apply { mkdirs() }
    private val passwordDir = File(context.filesDir, "passwords").apply { mkdirs() }
    private val _identities = MutableStateFlow(load())
    val identities: StateFlow<List<Identity>> = _identities

    private fun load(): List<Identity> {
        val array = runCatching { JSONArray(prefs.getString("identities", "[]")) }.getOrNull() ?: return emptyList()
        return List(array.length()) { Identity.fromJson(array.getJSONObject(it)) }
    }

    private fun save(identities: List<Identity>) {
        val array = JSONArray()
        identities.forEach { array.put(it.toJson()) }
        prefs.edit().putString("identities", array.toString()).apply()
        _identities.value = identities
    }

    fun get(id: String?): Identity? = _identities.value.firstOrNull { it.id == (id ?: DEVICE) }

    /** The device key, generated on first use. */
    fun device(deviceName: String): Identity = synchronized(this) {
        get(DEVICE) ?: store(DEVICE, deviceName, Native.generateKey("bellterm@${modelName()}"), null)
    }

    fun generate(name: String): Identity = synchronized(this) {
        store(UUID.randomUUID().toString(), name, Native.generateKey("bellterm@${modelName()}"), null)
    }

    /**
     * Import key text, decrypting it with [passphrase] when protected. Throws
     * [java.io.IOException] with the core's reason when it cannot be read.
     */
    fun import(name: String, text: String, passphrase: String?): Identity = synchronized(this) {
        val imported = JSONObject(Native.importKey(text, passphrase))
        store(UUID.randomUUID().toString(), name, imported.getString("private"), imported.getJSONObject("info"))
    }

    private fun store(id: String, name: String, private: String, info: JSONObject?): Identity {
        val details = info ?: JSONObject(Native.importKey(private, null)).getJSONObject("info")
        File(dir, id).writeBytes(encrypt(private.toByteArray()))
        val identity = Identity(
            id = id,
            name = name,
            algorithm = details.getString("algorithm"),
            fingerprint = details.getString("fingerprint"),
            publicKey = details.getString("public"),
        )
        save(_identities.value.filterNot { it.id == id } + identity)
        return identity
    }

    fun privateKey(id: String?): String = String(decrypt(File(dir, id ?: DEVICE).readBytes()))

    /** Validates [certificate] against the identity's key before attaching it. */
    fun certificateInfo(id: String, certificate: String): CertificateInfo =
        CertificateInfo.parse(Native.certificateInfo(certificate, privateKey(id)))

    fun setCertificate(id: String, certificate: String?) = synchronized(this) {
        save(_identities.value.map { if (it.id == id) it.copy(certificate = certificate?.trim()) else it })
    }

    fun rename(id: String, name: String) = synchronized(this) {
        save(_identities.value.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun delete(id: String) = synchronized(this) {
        File(dir, id).delete()
        save(_identities.value.filterNot { it.id == id })
    }

    // Passwords, one file per node ID, sealed like private keys.

    fun hasPassword(nodeId: String): Boolean = File(passwordDir, nodeId).isFile

    /** The node's saved password, or null when none is saved or it no longer decrypts. */
    fun password(nodeId: String): String? = synchronized(this) {
        val file = File(passwordDir, nodeId).takeIf { it.isFile } ?: return null
        runCatching { String(decrypt(file.readBytes())) }.getOrNull()
    }

    fun savePassword(nodeId: String, password: String) = synchronized(this) {
        File(passwordDir, nodeId).writeBytes(encrypt(password.toByteArray()))
    }

    fun forgetPassword(nodeId: String) = synchronized(this) {
        File(passwordDir, nodeId).delete()
    }

    private fun secret(): SecretKey {
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

    /** IV length, then the IV, then the ciphertext and tag. */
    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun decrypt(sealed: ByteArray): ByteArray {
        val ivLength = sealed[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, sealed, 1, ivLength))
        return cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
    }

    private fun modelName() = android.os.Build.MODEL.filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "android" }

    companion object {
        const val DEVICE = "device"
        private const val ALIAS = "bellterm-keychain"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
