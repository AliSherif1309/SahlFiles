package com.sahl.files

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Private vault. Files are encrypted with AES-256-GCM in 1 MB chunks and kept inside the app's private storage.
 *
 * One random master key encrypts everything. That master key is stored twice, wrapped:
 *  - by a key derived from your PIN (PBKDF2, 150 000 rounds) — always works, even after a phone reset of fingerprints;
 *  - optionally by an Android Keystore key that only unlocks after fingerprint / screen-lock confirmation.
 * Nothing is ever sent anywhere. If you forget the PIN and fingerprint stops working, the files cannot be recovered.
 */
object Vault {
    private const val CHUNK = 1024 * 1024
    private const val BIO_ALIAS = "sahl_vault_bio"
    private val rnd = SecureRandom()

    class Entry(val id: String, val name: String, val size: Long, val added: Long, val from: String)

    @Volatile var master: SecretKey? = null
    val unlocked get() = master != null

    fun dir(c: Context) = File(c.filesDir, "vault").apply { mkdirs() }
    private fun pinFile(c: Context) = File(dir(c), "pin.key")
    private fun bioFile(c: Context) = File(dir(c), "bio.key")
    private fun indexFile(c: Context) = File(dir(c), "index.enc")
    fun dataFile(c: Context, id: String) = File(dir(c), "$id.bin")
    fun openDir(c: Context) = File(c.cacheDir, "vault_open")

    fun isSetUp(c: Context) = pinFile(c).exists()
    fun bioEnabled(c: Context) = bioFile(c).exists()

    fun lock(c: Context) { master = null; openDir(c).deleteRecursively() }

    // ---------------------------------------------------------------- small helpers

    private fun seal(key: SecretKey, data: ByteArray, aad: ByteArray? = null): ByteArray {
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.ENCRYPT_MODE, key)                // the provider picks a fresh random IV
        if (aad != null) ci.updateAAD(aad)
        val ct = ci.doFinal(data)
        return ci.iv + ct
    }

    private fun open(key: SecretKey, blob: ByteArray, aad: ByteArray? = null): ByteArray {
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        if (aad != null) ci.updateAAD(aad)
        return ci.doFinal(blob, 12, blob.size - 12)
    }

    private fun pinKey(pin: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 150_000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    // ---------------------------------------------------------------- PIN

    fun setup(c: Context, pin: String) {
        val m = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        writePin(c, m, pin)
        master = m
        saveIndex(c, emptyList())
    }

    private fun writePin(c: Context, m: SecretKey, pin: String) {
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        pinFile(c).writeBytes(salt + seal(pinKey(pin, salt), m.encoded))
    }

    fun unlockPin(c: Context, pin: String): Boolean = try {
        val b = pinFile(c).readBytes()
        val raw = open(pinKey(pin, b.copyOfRange(0, 16)), b.copyOfRange(16, b.size))
        master = SecretKeySpec(raw, "AES"); true
    } catch (e: Exception) { false }

    fun changePin(c: Context, pin: String) { master?.let { writePin(c, it, pin) } }

    // ---------------------------------------------------------------- fingerprint (Android Keystore)

    /** Creates the Keystore key (needs a screen lock). Use it within 30 s of a successful BiometricPrompt. */
    fun prepareBioKey(): Boolean = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(BIO_ALIAS)) {
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setInvalidatedByBiometricEnrollment(false)
                .build())
            g.generateKey()
        }
        true
    } catch (e: Exception) { false }

    private fun bioKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(BIO_ALIAS, null) as? SecretKey
    } catch (e: Exception) { null }

    /** Call right after a successful prompt, while unlocked. */
    fun enableBio(c: Context): Boolean = try {
        val k = bioKey()!!; val m = master!!
        bioFile(c).writeBytes(seal(k, m.encoded)); true
    } catch (e: Exception) { false }

    fun disableBio(c: Context) { bioFile(c).delete() }

    /** Call right after a successful prompt. */
    fun unlockBio(c: Context): Boolean = try {
        val raw = open(bioKey()!!, bioFile(c).readBytes())
        master = SecretKeySpec(raw, "AES"); true
    } catch (e: Exception) { false }

    // ---------------------------------------------------------------- index (file list, also encrypted)

    fun entries(c: Context): List<Entry> = try {
        val a = JSONArray(String(open(master!!, indexFile(c).readBytes(), "index".toByteArray())))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Entry(it.getString("id"), it.getString("name"), it.getLong("size"), it.getLong("added"), it.optString("from")) } }
            .sortedByDescending { it.added }
    } catch (e: Exception) { emptyList() }

    @Synchronized private fun saveIndex(c: Context, list: List<Entry>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("added", it.added).put("from", it.from)) }
        val tmp = File(dir(c), "index.tmp")
        tmp.writeBytes(seal(master!!, a.toString().toByteArray(), "index".toByteArray()))
        tmp.renameTo(indexFile(c))
    }

    // ---------------------------------------------------------------- files

    private fun aad(id: String, i: Int, last: Boolean) = "$id:$i:${if (last) 1 else 0}".toByteArray()

    /** Encrypts [input] into the vault. [tick] gets bytes done. */
    fun add(c: Context, name: String, from: String, input: InputStream, tick: (Long) -> Unit): Boolean {
        val m = master ?: return false
        val id = UUID.randomUUID().toString()
        val out = dataFile(c, id)
        try {
            var done = 0L
            DataOutputStream(out.outputStream().buffered(256 * 1024)).use { o ->
                input.use { inp ->
                    val buf = ByteArray(CHUNK); var i = 0
                    var n = readFully(inp, buf)
                    while (true) {
                        val next = if (n == CHUNK) ByteArray(CHUNK) else null
                        val nextN = if (next != null) readFully(inp, next) else -1
                        val last = next == null || nextN <= 0
                        val blob = seal(m, buf.copyOf(maxOf(n, 0)), aad(id, i, last))
                        o.writeInt(blob.size); o.write(blob)
                        done += maxOf(n, 0); tick(done); i++
                        if (last) break
                        System.arraycopy(next!!, 0, buf, 0, nextN); n = nextN
                    }
                }
            }
            synchronized(this) { saveIndex(c, entries(c) + Entry(id, name, done, System.currentTimeMillis(), from)) }
            return true
        } catch (e: Exception) { out.delete(); return false }
    }

    private fun readFully(i: InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) { val n = i.read(b, off, b.size - off); if (n < 0) break; off += n }
        return off
    }

    /** Decrypts an entry into [out]. Fails if anything was changed or cut. */
    fun export(c: Context, e: Entry, out: OutputStream, tick: (Long) -> Unit): Boolean {
        val m = master ?: return false
        return try {
            DataInputStream(dataFile(c, e.id).inputStream().buffered(256 * 1024)).use { inp ->
                out.use { o ->
                    var i = 0; var done = 0L; var sawLast = false
                    while (!sawLast) {
                        val b0 = inp.read(); if (b0 < 0) break
                        val len = (b0 shl 24) or (inp.readUnsignedByte() shl 16) or (inp.readUnsignedByte() shl 8) or inp.readUnsignedByte()
                        val blob = ByteArray(len); inp.readFully(blob)
                        val plain = try { open(m, blob, aad(e.id, i, false)) }
                                    catch (_: Exception) { sawLast = true; open(m, blob, aad(e.id, i, true)) }
                        o.write(plain); done += plain.size; tick(done); i++
                    }
                    if (!sawLast) throw IllegalStateException("truncated")
                }
            }
            true
        } catch (ex: Exception) { false }
    }

    fun remove(c: Context, e: Entry) {
        dataFile(c, e.id).delete()
        synchronized(this) { saveIndex(c, entries(c).filter { it.id != e.id }) }
    }
}
