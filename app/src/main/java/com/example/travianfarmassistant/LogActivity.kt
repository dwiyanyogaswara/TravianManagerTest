package com.example.travianfarmassistant

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : Activity() {
    private val logFileName = "farm_assistant.log"
    private val logMaxAgeMs = 12 * 60 * 60 * 1000L
    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        debugTrace("ENTER onCreate")
        super.onCreate(savedInstanceState)
        title = "Log Aktivitas"

        pruneLogs()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = "LOG AKTIVITAS (12 JAM TERAKHIR)"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val refresh = Button(this).apply {
            text = "REFRESH"
            setOnClickListener { showLogs() }
        }
        header.addView(refresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val clear = Button(this).apply {
            text = "HAPUS LOG"
            setOnClickListener {
                try {
                    getFileStreamPath(logFileName).delete()
                } catch (_: Exception) {
                    // Ignore logging UI cleanup errors.
                }
                showLogs()
            }
        }
        val clearParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clearParams.marginStart = 8
        header.addView(clear, clearParams)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            setFillViewport(true)
        }
        val logView = TextView(this).apply {
            id = android.R.id.text1
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
        }
        scroll.addView(logView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        showLogs()
    }

    private fun showLogs() {
        debugTrace("ENTER showLogs")
        val logView = findViewById<TextView>(android.R.id.text1)
        pruneLogs()
        try {
            val file = getFileStreamPath(logFileName)
            if (!file.exists()) {
                logView.text = "Belum ada log."
                return
            }
            val lines = file.readLines()
            logView.text = if (lines.isEmpty()) "Belum ada log." else buildColoredLog(lines)
        } catch (_: Exception) {
            logView.text = "Gagal membaca log."
        }
    }

    private fun buildColoredLog(lines: List<String>): CharSequence {
        debugTrace("ENTER buildColoredLog")
        val palette = intArrayOf(
            Color.rgb(245, 166, 35),
            Color.rgb(30, 100, 210),
            Color.rgb(30, 140, 80),
            Color.rgb(125, 70, 180),
            Color.rgb(220, 95, 35),
            Color.rgb(0, 125, 145)
        )
        val out = SpannableString(lines.joinToString("\n"))
        var offset = 0
        lines.forEach { line ->
            val match = Regex("\\[CYCLE (\\d+)\\]").find(line)
            val color = if (match != null) {
                val number = match.groupValues[1].toIntOrNull() ?: 0
                palette[((number - 1).coerceAtLeast(0)) % palette.size]
            } else Color.DKGRAY
            val end = offset + line.length
            out.setSpan(android.text.style.ForegroundColorSpan(color), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            offset = end + 1
        }
        return out
    }

    /** Verbose diagnostics for the Log screen. */
    private fun debugTrace(message: String) {
        android.util.Log.d("TravianFarmAssistant", "[DEBUG] $message")
    }

    private fun pruneLogs() {
        debugTrace("ENTER pruneLogs")
        try {
            val file = getFileStreamPath(logFileName)
            if (!file.exists()) return
            val cutoff = System.currentTimeMillis() - logMaxAgeMs
            val kept = file.readLines().filter { line ->
                try {
                    val stamp = line.substringBefore(" | ")
                    val time = logTimeFormat.parse(stamp)?.time ?: return@filter false
                    time >= cutoff
                } catch (_: Exception) {
                    false
                }
            }
            file.writeText(kept.joinToString("\n") + if (kept.isNotEmpty()) "\n" else "")
        } catch (_: Exception) {
            // Never interrupt the main automation because of logging.
        }
    }
}
