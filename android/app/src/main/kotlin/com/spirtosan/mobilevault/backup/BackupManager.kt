package com.spirtosan.mobilevault.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.SecureRandom

/**
 * The pairing with the owner's server and the backup status. Files in `filesDir/backup/`:
 * `config.json` (url, pins, device name, key alias), `client.pem` / `ca.pem` (public),
 * `token.bin` (bearer token encrypted with a Keystore AES key), `status.json`.
 */
class BackupManager(filesDir: File, private val keys: DeviceKeys) {
    private val dir = File(filesDir, "backup")
    private val configFile = File(dir, "config.json")
    private val tokenFile = File(dir, "token.bin")
    private val statusFile = File(dir, "status.json")

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<BackupConfig?> = _config

    private val _status = MutableStateFlow(JsonFiles.read(statusFile)?.let { runCatching { BackupStatus.fromJson(it) }.getOrNull() } ?: BackupStatus())
    val status: StateFlow<BackupStatus> = _status

    private fun loadConfig(): BackupConfig? = JsonFiles.read(configFile)?.let { runCatching { BackupConfig.fromJson(it) }.getOrNull() }

    val isPaired get() = _config.value != null

    /** Null when not paired, or when the key or token is gone (e.g. data restored without Keystore). */
    fun target(): BackupTarget? {
        val c = _config.value ?: return null
        if (!keys.hasKey(c.keyAlias) || !tokenFile.isFile) return null
        return HttpBackupTarget(c.url, c.pins, keys.keyManager(c.keyAlias)) {
            String(keys.decrypt(tokenFile.readBytes()), Charsets.UTF_8)
        }
    }

    fun requireTarget(): BackupTarget = target()
        ?: throw BackupException(BackupException.Kind.NOT_PAIRED, "This phone is not connected to a backup server.")

    /**
     * New Keystore key → CSR → `POST /pair` → certificate on the key, token encrypted, config saved.
     * An existing pairing is replaced only after the new one succeeded.
     */
    suspend fun pair(p: PairingPayload): BackupConfig {
        val alias = DeviceKeys.CLIENT_ALIAS_PREFIX + ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        try {
            val gen = keys.generateClientKey(alias)
            val csr = Csr.pem(Csr.build("Mobile Vault phone", gen.publicKey.encoded) { keys.sign(alias, it) })
            val r = HttpBackupTarget.pair(p, csr)
            keys.installChain(alias, r.certPem, r.caPem)
            val old = _config.value
            JsonFiles.writeBytes(tokenFile, keys.encrypt(r.token.toByteArray(Charsets.UTF_8)))
            JsonFiles.writeBytes(File(dir, "client.pem"), r.certPem.toByteArray())
            JsonFiles.writeBytes(File(dir, "ca.pem"), r.caPem.toByteArray())
            val c = BackupConfig(p.url, p.pins, r.device, alias, System.currentTimeMillis(), gen.strongBox)
            JsonFiles.write(configFile, c.toJson())
            if (old != null && old.keyAlias != alias) keys.delete(old.keyAlias)
            _config.value = c
            // A new pairing starts clean; what is on the server is checked by the next upload.
            updateStatus { BackupStatus(testRestoreAt = it.testRestoreAt, testRestoreText = it.testRestoreText) }
            return c
        } catch (e: Exception) {
            keys.delete(alias)
            throw e
        }
    }

    /** Forget the pairing on this phone (key, token, certificate). The server entry must be revoked there. */
    fun disconnect() {
        _config.value?.let { keys.delete(it.keyAlias) }
        keys.deleteTokenKey()
        listOf(configFile, tokenFile, File(dir, "client.pem"), File(dir, "ca.pem"), statusFile).forEach { it.delete() }
        _config.value = null
        _status.value = BackupStatus()
    }

    @Synchronized
    fun updateStatus(change: (BackupStatus) -> BackupStatus): BackupStatus {
        val s = change(_status.value)
        if (_config.value != null || configFile.isFile) JsonFiles.write(statusFile, s.toJson())
        _status.value = s
        return s
    }

    /** Record a failure; 401/403 marks the phone as disconnected. */
    fun recordError(e: BackupException) = updateStatus {
        it.copy(lastError = e.text, disconnected = it.disconnected || e.kind == BackupException.Kind.DISCONNECTED,
            stopped = !e.retryable)
    }

    /** "Test connection": `/health`, then `/latest` for this vault (if there is one). Returns the text to show. */
    suspend fun testConnection(vaultId: String?): String {
        val t = requireTarget()
        try {
            t.health()
            // /health needs no auth; /latest proves that the certificate and the token work
            val latest = t.latest(vaultId ?: "00000000-0000-0000-0000-000000000000")
            updateStatus { it.copy(disconnected = false) }
            return if (vaultId == null) "Connection OK."
            else if (latest == null) "Connection OK. No backup of this vault on the server yet."
            else "Connection OK. Latest backup on the server: revision ${latest.rev}."
        } catch (e: BackupException) {
            if (e.kind == BackupException.Kind.DISCONNECTED) recordError(e)
            throw e
        }
    }
}
