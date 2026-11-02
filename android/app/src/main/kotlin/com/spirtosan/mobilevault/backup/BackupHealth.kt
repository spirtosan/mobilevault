package com.spirtosan.mobilevault.backup

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

    fun of(paired: Boolean, s: BackupStatus, localRev: Long, now: Long): Chip {
        val unsynced = paired && localRev > s.lastSuccessRev
        if (!paired) return Chip(Level.RED, "No backup set up", false)
        if (s.disconnected) return Chip(Level.RED, "Disconnected from the backup server", unsynced)
        if (s.stopped && s.lastError != null) return Chip(Level.RED, "Backup error", unsynced)
        if (s.lastSuccessAt <= 0) return Chip(Level.AMBER, "Not backed up yet", unsynced)
        val age = (now - s.lastSuccessAt).coerceAtLeast(0)
        val days = (age / DAY).toInt()
        return when {
            days >= RED_DAYS -> Chip(Level.RED, "Not backed up for $days days", unsynced)
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

/** "Test your backup" reminder: every 30 days after the last test (or pairing / dismissing), once something is backed up. */
object TestRestoreReminder {
    const val DAYS = 30
    fun due(paired: Boolean, s: BackupStatus, pairedAt: Long, dismissedAt: Long, now: Long): Boolean =
        paired && !s.disconnected && s.lastSuccessAt > 0 &&
            now - maxOf(s.testRestoreAt, pairedAt, dismissedAt) >= DAYS * 24 * 3_600_000L
}
