package me.saffer.sill.windows

import me.saffer.sill.core.Base64Url
import me.saffer.sill.core.Identity
import java.io.File
import java.security.SecureRandom
import java.util.Collections

/**
 * Everything the host keeps between launches: %LOCALAPPDATA%\Sill\ (HostState.DIR_PROP or
 * SILL_HOST_DIR overrides it for tests; ~/.sill-host when neither LOCALAPPDATA nor the override is
 * set, e.g. on the Mac while developing). Files are plain, readable by this user account only in
 * effect (no other account normally has the directory); shortcut: real DPAPI protection of the
 * private key is a later upgrade, once this phase proves the door works end to end.
 */
class HostState(val dir: File) {
    val identity: Identity
    val recognitionKey: ByteArray
    val launchID: String = java.util.UUID.randomUUID().toString()
    private val trustFile = File(dir, "trusted.txt")
    private val trusted: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    init {
        dir.mkdirs()
        identity = loadOrCreateIdentity()
        recognitionKey = loadOrCreateRecognitionKey()
        if (trustFile.exists()) trustFile.readLines().filter { it.isNotBlank() }.forEach { trusted.add(it.trim()) }
    }

    /** base64url fingerprints of devices this host has paired with. */
    fun isTrusted(fingerprint: ByteArray) = trusted.contains(Base64Url.encode(fingerprint))

    fun trust(fingerprint: ByteArray) {
        val enc = Base64Url.encode(fingerprint)
        if (trusted.add(enc)) trustFile.appendText(enc + "\n")
    }

    fun untrust(fingerprint: ByteArray) {
        trusted.remove(Base64Url.encode(fingerprint))
        trustFile.writeText(trusted.joinToString("\n", postfix = if (trusted.isEmpty()) "" else "\n"))
    }

    private fun loadOrCreateIdentity(): Identity {
        val keyFile = File(dir, "identity.key")
        val pointFile = File(dir, "identity.pub")
        if (keyFile.exists() && pointFile.exists()) {
            Identity.restore(keyFile.readBytes(), pointFile.readBytes())?.let { return it }
            Log.warn("the stored identity did not restore; generating a new one (the Mac ID and pin will change)")
        }
        val id = Identity.generate()
        keyFile.writeBytes(id.exportPrivate())
        pointFile.writeBytes(id.point)
        return id
    }

    private fun loadOrCreateRecognitionKey(): ByteArray {
        val f = File(dir, "recognition.key")
        if (f.exists()) { val b = f.readBytes(); if (b.size == 32) return b }
        val k = ByteArray(32).also(SecureRandom()::nextBytes)
        f.writeBytes(k)
        return k
    }

    companion object {
        const val DIR_PROP = "sill.hostDir"

        fun defaultDir(): File {
            System.getProperty(DIR_PROP)?.let { return File(it) }
            System.getenv("SILL_HOST_DIR")?.let { return File(it) }
            System.getenv("LOCALAPPDATA")?.let { return File(it, "Sill") }
            return File(System.getProperty("user.home"), ".sill-host")
        }
    }
}
