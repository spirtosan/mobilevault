package com.spirtosan.mobilevault.data

import com.spirtosan.mobilevault.crypto.Pvault2
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset

/**
 * The vault file on internal storage. Only encrypted PVAULT2 bytes ever touch the disk.
 *
 * - [save] is atomic: write `vault.pvault.tmp`, fsync, rename over `vault.pvault`.
 * - Before every save the current file is copied to `snapshots/`, then [SnapshotPolicy] prunes them.
 */
class VaultRepository(dir: File, private val clock: () -> Instant = Instant::now) {
    val vaultFile = File(dir, "vault.pvault")
    private val tmpFile = File(dir, "vault.pvault.tmp")
    val snapshotDir = File(dir, "snapshots")

    fun exists() = vaultFile.isFile

    fun read(): ByteArray = vaultFile.readBytes()

    @Synchronized
    fun save(bytes: ByteArray) {
        require(Pvault2.isPvault2(bytes)) { "refusing to save something that is not PVAULT2" }
        if (vaultFile.isFile) snapshotCurrent()
        tmpFile.delete()
        FileOutputStream(tmpFile).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        Files.move(tmpFile.toPath(), vaultFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        pruneSnapshots()
    }

    fun snapshots(): List<Snapshot> = snapshotDir.listFiles().orEmpty().mapNotNull { Snapshot.parse(it) }
        .sortedByDescending { it.time }

    private fun snapshotCurrent() {
        snapshotDir.mkdirs()
        val raw = vaultFile.readBytes()
        val rev = runCatching { Pvault2.peekHeader(raw).rev }.getOrDefault(0L)
        var t = clock().toEpochMilli()
        var f = File(snapshotDir, Snapshot.name(t, rev))
        while (f.exists()) f = File(snapshotDir, Snapshot.name(++t, rev))
        val tmp = File(snapshotDir, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(raw); it.fd.sync() }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun pruneSnapshots() {
        val all = snapshots()
        val keep = SnapshotPolicy.keep(all, clock())
        all.filter { it !in keep }.forEach { it.file.delete() }
    }
}

data class Snapshot(val file: File, val time: Instant, val rev: Long) {
    companion object {
        private val RE = Regex("^(\\d+)-r(\\d+)\\.pvault$")
        fun name(epochMs: Long, rev: Long) = "$epochMs-r$rev.pvault"
        fun parse(f: File): Snapshot? = RE.matchEntire(f.name)?.let {
            Snapshot(f, Instant.ofEpochMilli(it.groupValues[1].toLong()), it.groupValues[2].toLong())
        }
    }
}

/** Keep the newest [LAST] snapshots, plus the newest snapshot of each (UTC) day for the last [DAYS] days. */
object SnapshotPolicy {
    const val LAST = 20
    const val DAYS = 30L

    fun keep(snapshots: List<Snapshot>, now: Instant): Set<Snapshot> {
        val sorted = snapshots.sortedByDescending { it.time }
        val keep = sorted.take(LAST).toMutableSet()
        val oldest = now.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(DAYS - 1)
        sorted.groupBy { it.time.atOffset(ZoneOffset.UTC).toLocalDate() }
            .filterKeys { !it.isBefore(oldest) }
            .values.forEach { keep += it.first() }
        return keep
    }
}
