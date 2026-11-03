package com.spirtosan.mobilevault.backup

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The backup chip on the vault list (docs/BACKUP_AND_RECOVERY.md): green under 3 days, amber from
 * 3 days, red from 7 days, on a permanent error, when disconnected, or when no backup is set up.
 */
object BackupHealth {
    enum class Level { GREEN, AMBER, RED }

    data class Chip(val level: Level, val text: String, val unsynced: Boolean)

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    const val AMBER_DAYS = 3
    const val RED_DAYS = 7

    /** [certNotAfter]: expiry of this phone's client certificate (ms, 0 = unknown), see [CertExpiry]. */
    fun of(paired: Boolean, s: BackupStatus, localRev: Long, now: Long, certNotAfter: Long = 0, zone: ZoneId = ZoneId.systemDefault()): Chip {
        val unsynced = paired && localRev > s.lastSuccessRev
        if (!paired) return Chip(Level.RED, "No backup set up", false)
        if (s.disconnected) return Chip(Level.RED, "Disconnected from the backup server", unsynced)
        if (s.stopped && s.lastError != null) return Chip(Level.RED, "Backup error", unsynced)
        if (s.tlsFailures >= BackupStatus.TLS_RED && s.lastError != null) return Chip(Level.RED, "Backup error", unsynced)
        val age = (now - s.lastSuccessAt).coerceAtLeast(0)
        val days = (age / DAY).toInt()
        if (s.lastSuccessAt > 0 && days >= RED_DAYS) return Chip(Level.RED, "Not backed up for $days days", unsynced)
        if (CertExpiry.soon(certNotAfter, now))
            return Chip(Level.AMBER, "Reconnect to the backup server before ${CertExpiry.date(certNotAfter, zone)}", unsynced)
        if (s.lastSuccessAt <= 0) return Chip(Level.AMBER, "Not backed up yet", unsynced)
        return when {
            days >= AMBER_DAYS -> Chip(Level.AMBER, "Not backed up for $days days", unsynced)
            else -> Chip(Level.GREEN, "Backed up " + ago(age), unsynced)
        }
    }

    fun ago(ms: Long): String = when {
        ms < 60_000 -> "just now"
        ms < HOUR -> "${ms / 60_000} min ago"
        ms < DAY -> "${ms / HOUR} h ago"
        ms < 2 * DAY -> "1 day ago"
        else -> "${ms / DAY} days ago"
    }
}

/**
 * The client certificate is valid for `client_cert_days` (825) after pairing. After that nginx answers
 * 403 and backups stop, so the app warns [WARN_DAYS] days ahead (code review F7). Re-pairing renews it.
 */
object CertExpiry {
    const val WARN_DAYS = 30
    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** `notAfter` of the first certificate in [pem], in ms; null if it can't be read. */
    fun notAfter(pem: String): Long? = runCatching {
        val c = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
        c.notAfter.time
    }.getOrNull()

    /** From [WARN_DAYS] days before expiry (and after it). False when unknown (0). */
    fun soon(notAfter: Long, now: Long) = notAfter > 0 && notAfter - now <= WARN_DAYS * 24 * 3_600_000L

    fun date(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = DATE.format(Instant.ofEpochMilli(ms).atZone(zone))
}

/** "Test your backup" reminder: every 30 days after the last test (or pairing / dismissing), once something is backed up. */
object TestRestoreReminder {
    const val DAYS = 30
    fun due(paired: Boolean, s: BackupStatus, pairedAt: Long, dismissedAt: Long, now: Long): Boolean =
        paired && !s.disconnected && s.lastSuccessAt > 0 &&
            now - maxOf(s.testRestoreAt, pairedAt, dismissedAt) >= DAYS * 24 * 3_600_000L
}
