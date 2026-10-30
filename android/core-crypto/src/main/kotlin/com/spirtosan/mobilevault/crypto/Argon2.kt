/*
 * Mobile Vault – core crypto
 * Copyright (C) 2026  github.com/spirtosan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.spirtosan.mobilevault.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import java.lang.Long.rotateRight
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Argon2id v1.3 (RFC 9106), written for speed on Android's runtime.
 *
 * - All memory is one flat [LongArray] (128 longs per 1 KiB block); the inner loop allocates nothing.
 * - The BLAKE2b permutation works on local variables, so ART keeps the state in registers.
 * - Lanes run on their own threads and meet at each of the 4 segment boundaries per pass
 *   (RFC 9106 §3.4), so p = 4 uses 4 cores.
 *
 * Output is byte-identical to the RFC, BouncyCastle and argon2-cffi (see Argon2Test).
 * BLAKE2b itself (H0, H') comes from BouncyCastle; it is not on the hot path.
 */
internal object Argon2id {
    private const val VERSION = 0x13
    private const val TYPE_ID = 2
    private const val BLOCK_LONGS = 128
    private const val SYNC_POINTS = 4
    private const val M32 = 0xFFFFFFFFL
    private val EMPTY = ByteArray(0)

    fun hash(
        password: ByteArray, salt: ByteArray, mKiB: Int, t: Int, p: Int, outLen: Int = 32,
        secret: ByteArray = EMPTY, ad: ByteArray = EMPTY,
    ): ByteArray {
        require(p in 1..0xFFFFFF && t >= 1 && mKiB >= 8 * p && outLen >= 4) { "Argon2 parameters out of range" }
        val segLen = mKiB / (SYNC_POINTS * p)
        val laneLen = segLen * SYNC_POINTS
        val c = Ctx(LongArray(laneLen * p * BLOCK_LONGS), p, t, segLen, laneLen)
        val h0 = ByteArray(72)
        try {
            Blake2bDigest(512).run {
                for (x in intArrayOf(p, outLen, mKiB, t, VERSION, TYPE_ID)) le32(x)
                le32(password.size); update(password, 0, password.size)
                le32(salt.size); update(salt, 0, salt.size)
                le32(secret.size); update(secret, 0, secret.size)
                le32(ad.size); update(ad, 0, ad.size)
                doFinal(h0, 0)
            }
            for (lane in 0 until p) for (col in 0..1) {
                putLe32(h0, 64, col); putLe32(h0, 68, lane)
                val b = hPrime(1024, h0)
                ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
                    .get(c.mem, (lane * laneLen + col) * BLOCK_LONGS, BLOCK_LONGS)
                b.wipe()
            }
            fill(c)
            val fin = LongArray(BLOCK_LONGS)
            for (lane in 0 until p) {
                val o = (lane * laneLen + laneLen - 1) * BLOCK_LONGS
                for (i in 0 until BLOCK_LONGS) fin[i] = fin[i] xor c.mem[o + i]
            }
            val finBytes = ByteArray(1024)
            ByteBuffer.wrap(finBytes).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().put(fin)
            fin.fill(0)
            try { return hPrime(outLen, finBytes) } finally { finBytes.wipe() }
        } finally {
            c.mem.fill(0)
            h0.wipe()
        }
    }

    private class Ctx(val mem: LongArray, val p: Int, val t: Int, val segLen: Int, val laneLen: Int)

    /** Per-thread scratch blocks, allocated once per lane. */
    private class Scratch {
        val r = LongArray(BLOCK_LONGS)
        val tmp = LongArray(BLOCK_LONGS)
        val addr = LongArray(BLOCK_LONGS)
        val input = LongArray(BLOCK_LONGS)
        val zero = LongArray(BLOCK_LONGS)
        fun wipe() { r.fill(0); tmp.fill(0); addr.fill(0); input.fill(0) }
    }

    private fun fill(c: Ctx) {
        val scratch = Array(c.p) { Scratch() }
        try {
            if (c.p == 1) {
                for (pass in 0 until c.t) for (slice in 0 until SYNC_POINTS) fillSegment(c, pass, slice, 0, scratch[0])
                return
            }
            val pool = Executors.newFixedThreadPool(c.p) { r -> Thread(r, "argon2-lane").apply { isDaemon = true } }
            try {
                for (pass in 0 until c.t) for (slice in 0 until SYNC_POINTS) {
                    // invokeAll returns once every lane has finished this segment: the sync point.
                    val futures = pool.invokeAll((0 until c.p).map { lane ->
                        Callable { fillSegment(c, pass, slice, lane, scratch[lane]) }
                    })
                    try { futures.forEach { it.get() } } catch (e: ExecutionException) { throw e.cause ?: e }
                }
            } finally { pool.shutdownNow() }
        } finally { scratch.forEach { it.wipe() } }
    }

    private fun fillSegment(c: Ctx, pass: Int, slice: Int, lane: Int, s: Scratch) {
        val mem = c.mem
        val dataIndependent = pass == 0 && slice < SYNC_POINTS / 2
        if (dataIndependent) {
            s.input.fill(0)
            s.input[0] = pass.toLong(); s.input[1] = lane.toLong(); s.input[2] = slice.toLong()
            s.input[3] = (c.laneLen * c.p).toLong(); s.input[4] = c.t.toLong(); s.input[5] = TYPE_ID.toLong()
        }
        var start = 0
        if (pass == 0 && slice == 0) {
            start = 2
            if (dataIndependent) nextAddresses(s)
        }
        var cur = lane * c.laneLen + slice * c.segLen + start
        var prev = if (cur % c.laneLen == 0) cur + c.laneLen - 1 else cur - 1
        val startPos = if (pass == 0 || slice == SYNC_POINTS - 1) 0L else ((slice + 1) * c.segLen).toLong()
        for (i in start until c.segLen) {
            val rand = if (dataIndependent) {
                if (i % BLOCK_LONGS == 0) nextAddresses(s)
                s.addr[i % BLOCK_LONGS]
            } else mem[prev * BLOCK_LONGS]
            val refLane = if (pass == 0 && slice == 0) lane else ((rand ushr 32) % c.p).toInt()
            val area: Long = if (pass == 0) {
                when {
                    slice == 0 -> (i - 1).toLong()
                    refLane == lane -> (slice * c.segLen + i - 1).toLong()
                    else -> (slice * c.segLen + if (i == 0) -1 else 0).toLong()
                }
            } else {
                if (refLane == lane) (c.laneLen - c.segLen + i - 1).toLong()
                else (c.laneLen - c.segLen + if (i == 0) -1 else 0).toLong()
            }
            val j1 = rand and M32
            val x = (j1 * j1) ushr 32
            val rel = area - 1 - ((area * x) ushr 32)
            val ref = refLane * c.laneLen + ((startPos + rel) % c.laneLen).toInt()
            fillBlock(mem, prev * BLOCK_LONGS, mem, ref * BLOCK_LONGS, mem, cur * BLOCK_LONGS, pass != 0, s)
            prev = cur
            cur++
        }
    }

    private fun nextAddresses(s: Scratch) {
        s.input[6]++
        fillBlock(s.zero, 0, s.input, 0, s.addr, 0, false, s)
        fillBlock(s.zero, 0, s.addr, 0, s.addr, 0, false, s)
    }

    /** dst = G(x, y) on pass 0, dst ^= G(x, y) on later passes (Argon2 v1.3). */
    private fun fillBlock(
        x: LongArray, xo: Int, y: LongArray, yo: Int, dst: LongArray, d: Int, withXor: Boolean, s: Scratch,
    ) {
        val r = s.r
        val tmp = s.tmp
        for (i in 0 until BLOCK_LONGS) {
            val v = x[xo + i] xor y[yo + i]
            r[i] = v
            tmp[i] = if (withXor) v xor dst[d + i] else v
        }
        for (i in 0 until 8) {
            val b = 16 * i
            perm(r, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7,
                b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15)
        }
        for (i in 0 until 8) {
            val b = 2 * i
            perm(r, b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49,
                b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113)
        }
        for (i in 0 until BLOCK_LONGS) dst[d + i] = tmp[i] xor r[i]
    }

    /** BLAKE2b round without message words (RFC 9106 §3.6) on z[i0..i15]. Locals keep the state in registers. */
    private fun perm(
        z: LongArray, i0: Int, i1: Int, i2: Int, i3: Int, i4: Int, i5: Int, i6: Int, i7: Int,
        i8: Int, i9: Int, i10: Int, i11: Int, i12: Int, i13: Int, i14: Int, i15: Int,
    ) {
        var v0 = z[i0]
        var v1 = z[i1]
        var v2 = z[i2]
        var v3 = z[i3]
        var v4 = z[i4]
        var v5 = z[i5]
        var v6 = z[i6]
        var v7 = z[i7]
        var v8 = z[i8]
        var v9 = z[i9]
        var v10 = z[i10]
        var v11 = z[i11]
        var v12 = z[i12]
        var v13 = z[i13]
        var v14 = z[i14]
        var v15 = z[i15]
        v0 = v0 + v4 + 2L * (v0 and M32) * (v4 and M32); v12 = rotateRight(v12 xor v0, 32)
        v8 = v8 + v12 + 2L * (v8 and M32) * (v12 and M32); v4 = rotateRight(v4 xor v8, 24)
        v0 = v0 + v4 + 2L * (v0 and M32) * (v4 and M32); v12 = rotateRight(v12 xor v0, 16)
        v8 = v8 + v12 + 2L * (v8 and M32) * (v12 and M32); v4 = rotateRight(v4 xor v8, 63)
        v1 = v1 + v5 + 2L * (v1 and M32) * (v5 and M32); v13 = rotateRight(v13 xor v1, 32)
        v9 = v9 + v13 + 2L * (v9 and M32) * (v13 and M32); v5 = rotateRight(v5 xor v9, 24)
        v1 = v1 + v5 + 2L * (v1 and M32) * (v5 and M32); v13 = rotateRight(v13 xor v1, 16)
        v9 = v9 + v13 + 2L * (v9 and M32) * (v13 and M32); v5 = rotateRight(v5 xor v9, 63)
        v2 = v2 + v6 + 2L * (v2 and M32) * (v6 and M32); v14 = rotateRight(v14 xor v2, 32)
        v10 = v10 + v14 + 2L * (v10 and M32) * (v14 and M32); v6 = rotateRight(v6 xor v10, 24)
        v2 = v2 + v6 + 2L * (v2 and M32) * (v6 and M32); v14 = rotateRight(v14 xor v2, 16)
        v10 = v10 + v14 + 2L * (v10 and M32) * (v14 and M32); v6 = rotateRight(v6 xor v10, 63)
        v3 = v3 + v7 + 2L * (v3 and M32) * (v7 and M32); v15 = rotateRight(v15 xor v3, 32)
        v11 = v11 + v15 + 2L * (v11 and M32) * (v15 and M32); v7 = rotateRight(v7 xor v11, 24)
        v3 = v3 + v7 + 2L * (v3 and M32) * (v7 and M32); v15 = rotateRight(v15 xor v3, 16)
        v11 = v11 + v15 + 2L * (v11 and M32) * (v15 and M32); v7 = rotateRight(v7 xor v11, 63)
        v0 = v0 + v5 + 2L * (v0 and M32) * (v5 and M32); v15 = rotateRight(v15 xor v0, 32)
        v10 = v10 + v15 + 2L * (v10 and M32) * (v15 and M32); v5 = rotateRight(v5 xor v10, 24)
        v0 = v0 + v5 + 2L * (v0 and M32) * (v5 and M32); v15 = rotateRight(v15 xor v0, 16)
        v10 = v10 + v15 + 2L * (v10 and M32) * (v15 and M32); v5 = rotateRight(v5 xor v10, 63)
        v1 = v1 + v6 + 2L * (v1 and M32) * (v6 and M32); v12 = rotateRight(v12 xor v1, 32)
        v11 = v11 + v12 + 2L * (v11 and M32) * (v12 and M32); v6 = rotateRight(v6 xor v11, 24)
        v1 = v1 + v6 + 2L * (v1 and M32) * (v6 and M32); v12 = rotateRight(v12 xor v1, 16)
        v11 = v11 + v12 + 2L * (v11 and M32) * (v12 and M32); v6 = rotateRight(v6 xor v11, 63)
        v2 = v2 + v7 + 2L * (v2 and M32) * (v7 and M32); v13 = rotateRight(v13 xor v2, 32)
        v8 = v8 + v13 + 2L * (v8 and M32) * (v13 and M32); v7 = rotateRight(v7 xor v8, 24)
        v2 = v2 + v7 + 2L * (v2 and M32) * (v7 and M32); v13 = rotateRight(v13 xor v2, 16)
        v8 = v8 + v13 + 2L * (v8 and M32) * (v13 and M32); v7 = rotateRight(v7 xor v8, 63)
        v3 = v3 + v4 + 2L * (v3 and M32) * (v4 and M32); v14 = rotateRight(v14 xor v3, 32)
        v9 = v9 + v14 + 2L * (v9 and M32) * (v14 and M32); v4 = rotateRight(v4 xor v9, 24)
        v3 = v3 + v4 + 2L * (v3 and M32) * (v4 and M32); v14 = rotateRight(v14 xor v3, 16)
        v9 = v9 + v14 + 2L * (v9 and M32) * (v14 and M32); v4 = rotateRight(v4 xor v9, 63)
        z[i0] = v0
        z[i1] = v1
        z[i2] = v2
        z[i3] = v3
        z[i4] = v4
        z[i5] = v5
        z[i6] = v6
        z[i7] = v7
        z[i8] = v8
        z[i9] = v9
        z[i10] = v10
        z[i11] = v11
        z[i12] = v12
        z[i13] = v13
        z[i14] = v14
        z[i15] = v15
    }

    /** Variable-length hash H' (RFC 9106 §3.3). */
    private fun hPrime(outLen: Int, x: ByteArray): ByteArray {
        val out = ByteArray(outLen)
        if (outLen <= 64) {
            Blake2bDigest(outLen * 8).run { le32(outLen); update(x, 0, x.size); doFinal(out, 0) }
            return out
        }
        val r = (outLen + 31) / 32 - 2
        val v = ByteArray(64)
        Blake2bDigest(512).run { le32(outLen); update(x, 0, x.size); doFinal(v, 0) }
        System.arraycopy(v, 0, out, 0, 32)
        val d = Blake2bDigest(512)
        for (i in 1 until r) {
            d.update(v, 0, 64); d.doFinal(v, 0)
            System.arraycopy(v, 0, out, i * 32, 32)
        }
        val lastLen = outLen - 32 * r
        val last = ByteArray(lastLen)
        Blake2bDigest(lastLen * 8).run { update(v, 0, 64); doFinal(last, 0) }
        System.arraycopy(last, 0, out, 32 * r, lastLen)
        v.wipe(); last.wipe()
        return out
    }

    private fun Blake2bDigest.le32(x: Int) {
        for (i in 0..3) update((x ushr (8 * i)).toByte())
    }

    private fun putLe32(b: ByteArray, off: Int, x: Int) {
        for (i in 0..3) b[off + i] = (x ushr (8 * i)).toByte()
    }
}
