package com.spirtosan.mobilevault

import com.spirtosan.mobilevault.backup.BackupErrors
import com.spirtosan.mobilevault.backup.BackupException.Kind
import com.spirtosan.mobilevault.backup.BackupHealth
import com.spirtosan.mobilevault.backup.BackupHealth.Level
import com.spirtosan.mobilevault.backup.BackupStatus
import com.spirtosan.mobilevault.backup.BackupUploader
import com.spirtosan.mobilevault.backup.BackupUploader.Outcome
import com.spirtosan.mobilevault.backup.CertExpiry
import com.spirtosan.mobilevault.backup.PairingPayload
import com.spirtosan.mobilevault.crypto.Argon2Params
import com.spirtosan.mobilevault.crypto.Pvault2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Task 08 Part C: code review F3 (TLS retry), F6 (no pinless pairing in release), F7 (certificate expiry). */
class SmallFixesTest {
    private val h = 3_600_000L
    private val d = 24 * h

    // ── F3 ──
    @Test fun tlsFailuresRetryButPinMismatchStops() {
        assertTrue(BackupErrors.fromIo(SSLHandshakeException("Connection reset during handshake")).retryable)
        assertTrue(BackupErrors.fromIo(SSLPeerUnverifiedException("Hostname not verified")).retryable)
        val pin = BackupErrors.fromIo(SSLPeerUnverifiedException("Certificate pinning failure!"))
        assertEquals(Kind.PIN_MISMATCH, pin.kind)
        assertFalse(pin.retryable)
    }

    @Test fun threeTlsFailuresInARowTurnTheChipRed() = runBlocking {
        val fast = Argon2Params(mKiB = 32768, t = 2, p = 4)
        var status = BackupStatus()
        val file = Pvault2.create("pw".toCharArray(), "t", kdf = fast).bytes
        val target = FakeTarget()
        val up = BackupUploader(target, { file }, { status }, { status = it(status) }, clock = { 10 * d })
        fun chip() = BackupHealth.of(true, status, 1, 10 * d)

        target.failWith = BackupErrors.fromIo(SSLHandshakeException("handshake dropped"))
        assertEquals(Outcome.RETRY, up.run())
        assertEquals(Outcome.RETRY, up.run())
        assertEquals(2, status.tlsFailures)
        assertFalse(status.stopped)
        assertEquals(Level.AMBER, chip().level)                     // "Not backed up yet", still retrying
        assertEquals(Outcome.RETRY, up.run())
        assertEquals(3, status.tlsFailures)
        chip().let { assertEquals(Level.RED, it.level); assertEquals("Backup error", it.text) }

        target.failWith = null                                      // the network is fine again
        assertEquals(Outcome.SUCCESS, up.run())
        assertEquals(0, status.tlsFailures)
        assertEquals(Level.GREEN, chip().level)

        // another kind of error resets the TLS count; pin mismatch stops
        status = status.copy(tlsFailures = 2, lastSuccessRev = 0)
        target.failWith = BackupErrors.fromIo(java.net.UnknownHostException())
        assertEquals(Outcome.RETRY, up.run())
        assertEquals(0, status.tlsFailures)
        target.failWith = BackupErrors.fromIo(SSLPeerUnverifiedException("Certificate pinning failure!"))
        assertEquals(Outcome.STOPPED, up.run())
        assertTrue(status.stopped)
    }

    @Test fun tlsCountSurvivesRestart() {
        val s = BackupStatus(tlsFailures = 3, lastError = "x")
        assertEquals(3, BackupStatus.fromJson(s.toJson()).tlsFailures)
        assertEquals(0, BackupStatus.fromJson(BackupStatus().toJson()).tlsFailures)
    }

    // ── F6 ──
    @Test fun pinlessPairingOnlyInDebugBuilds() {
        assertFalse(PairingPayload.allowWithoutPins(debugBuild = false))
        assertTrue(PairingPayload.allowWithoutPins(debugBuild = true))
        assertTrue(PairingPayload.NO_PINS_TEXT.contains("mvault-admin pair"))
    }

    // ── F7 ──
    /** A throwaway self-signed test certificate (no key kept), valid until 5 Feb 2029 12:31:18 UTC. */
    private val testCert = """
-----BEGIN CERTIFICATE-----
MIIBjjCCATOgAwIBAgIUa7GRU2JOa4awTbsedVo2FErp7PAwCgYIKoZIzj0EAwIw
HDEaMBgGA1UEAwwRTW9iaWxlIFZhdWx0IHRlc3QwHhcNMjYxMTAzMTIzMTE4WhcN
MjkwMjA1MTIzMTE4WjAcMRowGAYDVQQDDBFNb2JpbGUgVmF1bHQgdGVzdDBZMBMG
ByqGSM49AgEGCCqGSM49AwEHA0IABFT9w848YCIkJn/M1CJMyXpE8dko8KLsyiWG
EVLilbY865HnXX4aC8LEI0kmlKBJZf+rRa/c+8c7YkTRSfx4IvmjUzBRMB0GA1Ud
DgQWBBTGGQkSkGBEPNWIlXma05Usijzg9jAfBgNVHSMEGDAWgBTGGQkSkGBEPNWI
lXma05Usijzg9jAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA0kAMEYCIQD5
rmPnFPNMiM+Tp6QhiHBcGVtxJ1JYZP7luX8Q/u+0yAIhAIfCp/jFqB0ryUD42TLJ
oIvAEoj2qSkzD0j3NcDpU7ZB
-----END CERTIFICATE-----
""".trimIndent()

    @Test fun certificateExpiryIsRead() {
        val na = CertExpiry.notAfter(testCert)!!
        assertEquals(java.time.Instant.parse("2029-02-05T12:31:18Z").toEpochMilli(), na)
        assertEquals("5 Feb 2029", CertExpiry.date(na, ZoneOffset.UTC))
        assertNull(CertExpiry.notAfter("not a certificate"))
    }

    @Test fun expiryWarningFrom30DaysBefore() {
        val na = 1000 * d
        assertFalse(CertExpiry.soon(na, na - 31 * d))
        assertTrue(CertExpiry.soon(na, na - 30 * d))
        assertTrue(CertExpiry.soon(na, na + d))                       // expired: still warns
        assertFalse(CertExpiry.soon(0, na))                           // unknown

        val ok = BackupStatus(lastSuccessAt = na - 30 * d - h, lastSuccessRev = 5)
        assertEquals(Level.GREEN, BackupHealth.of(true, ok, 5, na - 31 * d, na).level)
        BackupHealth.of(true, ok, 5, na - 30 * d, na, ZoneOffset.UTC).let {
            assertEquals(Level.AMBER, it.level)
            assertEquals("Reconnect to the backup server before " + CertExpiry.date(na, ZoneOffset.UTC), it.text)
        }
        // red states win over the amber expiry warning
        assertEquals(Level.RED, BackupHealth.of(true, ok.copy(disconnected = true), 5, na - 30 * d, na).level)
        assertEquals(Level.RED, BackupHealth.of(true, ok, 5, na - 20 * d, na).level)          // 10 days without backup
    }
}
