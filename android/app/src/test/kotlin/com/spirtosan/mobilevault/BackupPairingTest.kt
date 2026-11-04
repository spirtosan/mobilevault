package com.spirtosan.mobilevault

import com.spirtosan.mobilevault.backup.BackupErrors
import com.spirtosan.mobilevault.backup.BackupException.Kind
import com.spirtosan.mobilevault.backup.Csr
import com.spirtosan.mobilevault.backup.PairingPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class BackupPairingTest {
    // Placeholders only: the real URL and pins come from the QR at runtime (public repo).
    private val pin1 = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val pin2 = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
    private fun payload(extra: String = ""","pins":["$pin1","$pin2"]""", url: String = "https://vault.example.org/api/v1", code: String = "ABCDE23456") =
        """{"t":"mvault-pair","v":1,"url":"$url","code":"$code"$extra}"""

    private fun invalid(text: String) {
        try { PairingPayload.parse(text); fail("accepted: $text") } catch (e: PairingPayload.Invalid) { /* ok */ }
    }

    @Test fun parsesAdminPayload() {
        val p = PairingPayload.parse(payload())
        assertEquals("https://vault.example.org/api/v1", p.url)
        assertEquals("vault.example.org", p.host)
        assertEquals("ABCDE23456", p.code)
        assertEquals(listOf(pin1, pin2), p.pins)
    }

    @Test fun acceptsPastedLineAndFormattedCode() {
        val p = PairingPayload.parse("Payload: " + payload(code = "abcde-23456", url = "https://vault.example.org/api/v1/") + "\n")
        assertEquals("ABCDE23456", p.code)
        assertEquals("https://vault.example.org/api/v1", p.url)
    }

    @Test fun pinsAreOptionalButValidated() {
        assertTrue(PairingPayload.parse(payload(extra = "")).pins.isEmpty())
        invalid(payload(extra = ""","pins":["sha1/abc"]"""))
        invalid(payload(extra = ""","pins":"$pin1""""))
        invalid(payload(extra = ""","pins":["${pin1.dropLast(2)}"]"""))
        assertEquals(1, PairingPayload.parse(payload(extra = ""","pins":["$pin1","$pin1"]""")).pins.size)
    }

    @Test fun rejectsWrongTypeVersionUrlOrCode() {
        invalid("hello")
        invalid("{not json}")
        invalid(payload().replace("mvault-pair", "other"))
        invalid(payload().replace("\"v\":1", "\"v\":2"))
        invalid(payload(url = "http://vault.example.org/api/v1"))
        invalid(payload(url = "https://user@vault.example.org/api/v1"))
        invalid(payload(url = "ftp://vault.example.org"))
        invalid(payload(code = "ABCDE2345"))        // 9 chars
        invalid(payload(code = "ABCDE23450"))       // 0 is not in the alphabet
    }

    @Test fun csrIsValidPkcs10SignedByTheKey() {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = kpg.generateKeyPair()
        val der = Csr.build("Mobile Vault phone", kp.public.encoded) { data ->
            Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(data); sign() }
        }
        // CertificationRequest ::= SEQUENCE { info, algorithm, signature BIT STRING }
        val outer = Der(der).children()
        assertEquals(3, outer.size)
        val (info, alg, sig) = outer
        assertEquals(0x30, info.tag)
        val infoKids = info.children()
        assertArrayEquals(byteArrayOf(0), infoKids[0].content)                           // version 0
        assertArrayEquals(kp.public.encoded, infoKids[2].raw)                            // SubjectPublicKeyInfo
        assertEquals(0xA0, infoKids[3].tag)                                              // empty attributes
        assertTrue(String(infoKids[1].raw, Charsets.ISO_8859_1).contains("Mobile Vault phone"))
        assertArrayEquals(byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02), alg.children()[0].raw)
        assertEquals(0x03, sig.tag)
        assertEquals(0, sig.content[0].toInt())
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(kp.public); update(info.raw); verify(sig.content.copyOfRange(1, sig.content.size))
        }
        assertTrue("signature verifies", ok)
        val pem = Csr.pem(der)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE REQUEST-----\n"))
        // For a manual `openssl req -verify` check (no secret: a throw-away key).
        File("build/test-csr.pem").apply { parentFile?.mkdirs(); writeText(pem) }
    }

    @Test fun httpErrorsMapToPlainWords() {
        assertEquals(Kind.PAIR_CODE, BackupErrors.fromHttp(403, """{"error":"invalid or expired pairing code"}""", pairing = true).kind)
        assertTrue(BackupErrors.fromHttp(403, null, pairing = true, admin = true).text.contains("mvault-admin pair"))
        val revoked = BackupErrors.fromHttp(403, """{"error":"device revoked"}""")
        assertEquals(Kind.DISCONNECTED, revoked.kind)
        assertTrue(revoked.text.contains("disconnected from the server"))
        assertEquals(Kind.DISCONNECTED, BackupErrors.fromHttp(401, """{"error":"bad token"}""").kind)
        assertFalse(revoked.retryable)
        assertEquals(Kind.TOO_LARGE, BackupErrors.fromHttp(413, "<html>nginx</html>").kind)
        val bad = BackupErrors.fromHttp(422, """{"error":"vault_id/rev do not match the file header"}""")
        assertEquals(Kind.REJECTED, bad.kind)
        assertTrue(bad.text.contains("vault_id/rev do not match"))
        assertEquals(Kind.CONFLICT, BackupErrors.fromHttp(409, null).kind)
        assertEquals(Kind.NOT_FOUND, BackupErrors.fromHttp(404, """{"error":"no revisions"}""").kind)
        assertTrue(BackupErrors.fromHttp(502, "<html>").retryable)
        assertTrue(BackupErrors.fromHttp(429, "").retryable)
    }

    @Test fun ioErrorsMapToPlainWords() {
        val pin = BackupErrors.fromIo(SSLPeerUnverifiedException("Certificate pinning failure!\n  Peer certificate chain: …"))
        assertEquals(Kind.PIN_MISMATCH, pin.kind)
        assertTrue(pin.text.contains("not your server"))
        assertFalse(pin.retryable)
        assertEquals(Kind.TLS, BackupErrors.fromIo(SSLHandshakeException("Chain validation failed")).kind)
        assertEquals(Kind.NETWORK, BackupErrors.fromIo(UnknownHostException("vault.example.org")).kind)
        assertEquals(Kind.NETWORK, BackupErrors.fromIo(SocketTimeoutException()).kind)
        assertTrue(BackupErrors.fromIo(UnknownHostException()).retryable)
    }

    /** Minimal DER reader for the test. */
    private class Der(val raw: ByteArray) {
        val tag = raw[0].toInt() and 0xFF
        private val hdr: Int
        private val len: Int
        init {
            val b = raw[1].toInt() and 0xFF
            if (b < 0x80) { len = b; hdr = 2 } else {
                val n = b and 0x7F
                var l = 0
                for (i in 0 until n) l = (l shl 8) or (raw[2 + i].toInt() and 0xFF)
                len = l; hdr = 2 + n
            }
            assertEquals("element length", hdr + len, raw.size)
        }
        val content: ByteArray get() = raw.copyOfRange(hdr, hdr + len)

        fun children(): List<Der> {
            val c = content
            val out = mutableListOf<Der>()
            var i = 0
            while (i < c.size) {
                val b = c[i + 1].toInt() and 0xFF
                val (h, l) = if (b < 0x80) 2 to b else {
                    val n = b and 0x7F
                    var l = 0
                    for (k in 0 until n) l = (l shl 8) or (c[i + 2 + k].toInt() and 0xFF)
                    (2 + n) to l
                }
                out += Der(c.copyOfRange(i, i + h + l))
                i += h + l
            }
            return out
        }
    }
}
