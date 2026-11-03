package com.spirtosan.mobilevault.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The owner's mvault server (`https://…/api/v1`). TLS 1.2+, system CAs (no user CAs, see
 * network_security_config.xml) **and** the SPKI pins from the pairing QR, client certificate from
 * Android Keystore ([keyManager]) and `Authorization: Bearer`. Nothing is ever logged.
 */
class HttpBackupTarget(
    private val baseUrl: String,
    pins: List<String>,
    keyManager: KeyManager?,
    private val token: () -> String,
) : BackupTarget {
    private val client = client(baseUrl, pins, keyManager)

    override suspend fun health() {
        call(Request.Builder().url("$baseUrl/health").get().build(), auth = false).close()
    }

    override suspend fun upload(vaultId: String, rev: Long, bytes: ByteArray): UploadResult {
        val req = Request.Builder().url("$baseUrl/vaults/$vaultId/revs/$rev")
            .header("X-Content-SHA256", sha256Hex(bytes))
            .put(bytes.toRequestBody(OCTET))
            .build()
        return try {
            call(req).use { UploadResult.Stored(RemoteRev.fromJson(json(it))) }
        } catch (e: BackupException) {
            if (e.kind == BackupException.Kind.CONFLICT) UploadResult.Exists else throw e
        }
    }

    override suspend fun vaults(): List<RemoteVault> = call(get("/vaults")).use { r ->
        val o = json(r)
        try { RemoteVault.listFromJson(o) } catch (e: IllegalArgumentException) {
            throw BackupException(BackupException.Kind.SERVER, "Unexpected answer from the server.")
        }
    }

    override suspend fun list(vaultId: String): List<RemoteRev> =
        call(get("/vaults/$vaultId/revs")).use { r ->
            (json(r)["revs"] as? JsonArray ?: throw BackupException(BackupException.Kind.SERVER, "Unexpected answer from the server."))
                .map { RemoteRev.fromJson(it.jsonObject) }
        }

    override suspend fun download(vaultId: String, rev: Long): ByteArray = call(get("/vaults/$vaultId/revs/$rev")).use { r ->
        val bytes = withContext(Dispatchers.IO) { r.body!!.bytes() }
        val want = r.header("X-Content-SHA256")?.lowercase()
        if (want != null && want != sha256Hex(bytes))
            throw BackupException(BackupException.Kind.NETWORK, "The download was damaged on the way. Try again.")
        bytes
    }

    override suspend fun latest(vaultId: String): RemoteRev? = try {
        call(get("/vaults/$vaultId/latest")).use { RemoteRev.fromJson(json(it)) }
    } catch (e: BackupException) {
        if (e.kind == BackupException.Kind.NOT_FOUND) null else throw e
    }

    private fun get(path: String) = Request.Builder().url(baseUrl + path).get().build()

    private suspend fun call(req: Request, auth: Boolean = true): Response = withContext(Dispatchers.IO) {
        val r = req.newBuilder().apply { if (auth) header("Authorization", "Bearer " + token()) }.build()
        val resp = try { client.newCall(r).execute() } catch (e: IOException) { throw BackupErrors.fromIo(e) }
        if (!resp.isSuccessful) {
            val body = resp.use { runCatching { it.peekBody(4096).string() }.getOrNull() }
            throw BackupErrors.fromHttp(resp.code, body)
        }
        resp
    }

    private suspend fun json(r: Response): JsonObject = withContext(Dispatchers.IO) {
        try { Json.parseToJsonElement(r.body!!.string()).jsonObject } catch (e: IOException) {
            throw BackupErrors.fromIo(e)
        } catch (e: Exception) {
            throw BackupException(BackupException.Kind.SERVER, "Unexpected answer from the server.")
        }
    }

    /** Answer of `POST /pair`. */
    class Paired(val device: String, val certPem: String, val caPem: String, val token: String)

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
        private val JSON = "application/json".toMediaType()

        fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        /** `POST {url}/pair` with the one-time code and the CSR. No client certificate yet; pins apply. */
        suspend fun pair(p: PairingPayload, csrPem: String): Paired = withContext(Dispatchers.IO) {
            val body = JsonObject(mapOf("code" to JsonPrimitive(p.code), "csr_pem" to JsonPrimitive(csrPem))).toString()
            val req = Request.Builder().url("${p.url}/pair").post(body.toRequestBody(JSON)).build()
            val resp = try { client(p.url, p.pins, null).newCall(req).execute() } catch (e: IOException) { throw BackupErrors.fromIo(e) }
            resp.use { r ->
                val text = runCatching { r.body!!.string() }.getOrNull()
                if (r.code != 201) throw BackupErrors.fromHttp(r.code, text, pairing = true)
                try {
                    val o = Json.parseToJsonElement(text ?: "").jsonObject
                    fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException(k)
                    Paired(s("device"), s("cert_pem"), s("ca_pem"), s("token"))
                } catch (e: Exception) {
                    throw BackupException(BackupException.Kind.SERVER, "Unexpected answer from the server.")
                }
            }
        }

        private fun client(url: String, pins: List<String>, keyManager: KeyManager?): OkHttpClient {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val ssl = SSLContext.getInstance("TLS").apply { init(keyManager?.let { arrayOf(it) }, arrayOf(tm), null) }
            val host = url.toHttpUrlOrNull()?.host
                ?: throw BackupException(BackupException.Kind.NOT_PAIRED, "The stored server address is invalid.")
            return OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, tm)
                .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2).build()))
                .apply { if (pins.isNotEmpty()) certificatePinner(CertificatePinner.Builder().add(host, *pins.toTypedArray()).build()) }
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
        }
    }
}
