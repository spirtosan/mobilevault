package com.spirtosan.mobilevault.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** One A4 page: recovery key, vault ID and how to use it. Written straight to the SAF stream (never to app storage). */
object EmergencyKitPdf {
    fun write(out: OutputStream, recoveryKey: String, vaultId: String, created: String) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val c = page.canvas
            val title = Paint().apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val body = Paint().apply { textSize = 11f; isAntiAlias = true }
            val mono = Paint().apply { textSize = 18f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
            val small = Paint().apply { textSize = 9f; isAntiAlias = true; color = 0xFF555555.toInt() }
            var y = 70f
            c.drawText("Mobile Vault: Emergency Kit", 50f, y, title); y += 30
            c.drawText("Created $created", 50f, y, body); y += 40
            c.drawText("Recovery key", 50f, y, body); y += 28
            val groups = recoveryKey.split("-")
            c.drawText(groups.take(5).joinToString("-"), 50f, y, mono); y += 26
            c.drawText(groups.drop(5).joinToString("-"), 50f, y, mono); y += 36
            c.drawText("Vault ID: $vaultId", 50f, y, body); y += 40
            listOf(
                "What it is: the recovery key opens your vault if you forget the master password.",
                "",
                "How to use it:",
                "1. Open Mobile Vault and tap \"Forgot password? Use recovery key\" on the unlock screen.",
                "2. Type the key above (dashes and upper/lower case don't matter; O = 0, I/L = 1).",
                "3. Set a new master password. The recovery key stays valid.",
                "",
                "Keep this page offline, e.g. printed and stored with your important documents.",
                "Anyone with this key AND your vault file can open the vault. Do not photograph it or",
                "store it in cloud drives or email. If it is lost or exposed, create a new key in",
                "Settings > Regenerate recovery key (the old key then stops working).",
            ).forEach { c.drawText(it, 50f, y, body); y += 17 }
            c.drawText("github.com/spirtosan/mobilevault", 50f, 800f, small)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
