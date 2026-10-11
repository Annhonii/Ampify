package com.example.batteryrestrict

import android.content.Context
import com.topjohnwu.superuser.Shell

data class UsageItem(val name: String, val mah: Double)

data class PowerReport(
    val capacityMah: Double?,
    val apps: List<UsageItem>,
    val components: List<UsageItem>,
    val appsIncludeShared: Boolean
)

/**
 * Reads Android's own power accounting ("Estimated power use (mAh)" in `dumpsys batterystats`).
 * That command needs root. Apps are the "Uid ..." lines; everything else (screen, idle, cell
 * radio, Wi-Fi, Bluetooth, camera, ...) is a device component.
 */
object PowerStats {

    private val entry = Regex("^\\s*(.+?):\\s*(-?[0-9]+(?:\\.[0-9]+)?)(.*)$")
    private val smeared = Regex("Including smearing:\\s*([0-9]+(?:\\.[0-9]+)?)")
    private val capacity = Regex("Capacity:\\s*([0-9]+(?:\\.[0-9]+)?)")
    private val userApp = Regex("^[Uu]id\\s+u(\\d+)a(\\d+)$")
    private val plainUid = Regex("^[Uu]id\\s+(\\d+)$")

    /** Runs the root command. Call off the main thread. Returns null if nothing could be read. */
    fun load(context: Context): PowerReport? {
        val res = Shell.cmd(
            "dumpsys batterystats | sed -n '/Estimated power use (mAh)/,\$p' | head -500"
        ).exec()
        if (res.out.isEmpty()) return null
        return parse(context, res.out)
    }

    private fun indent(line: String) = line.takeWhile { it == ' ' }.length

    private fun parse(context: Context, lines: List<String>): PowerReport? {
        val start = lines.indexOfFirst { it.contains("Estimated power use (mAh)") }
        if (start < 0) return null
        val headerIndent = indent(lines[start])

        var cap: Double? = null
        var shared = false
        val apps = mutableListOf<UsageItem>()
        val comps = mutableListOf<UsageItem>()

        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank() || indent(line) <= headerIndent) break
            val trimmed = line.trim()
            if (trimmed.startsWith("Capacity:")) {
                cap = capacity.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()
                continue
            }
            val m = entry.find(line) ?: continue
            val name = m.groupValues[1].trim()
            var value = m.groupValues[2].toDoubleOrNull() ?: continue
            val rest = m.groupValues[3]
            if (name.startsWith("Uid", ignoreCase = true)) {
                smeared.find(rest)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                    value = it
                    shared = true
                }
                if (value >= 0.01) apps += UsageItem(resolveName(context, name), value)
            } else if (value >= 0.01) {
                comps += UsageItem(name, value)
            }
        }

        fun merge(list: List<UsageItem>) = list
            .groupBy { it.name }
            .map { (n, v) -> UsageItem(n, v.sumOf { it.mah }) }
            .sortedByDescending { it.mah }

        val a = merge(apps)
        val c = merge(comps)
        if (a.isEmpty() && c.isEmpty()) return null
        return PowerReport(cap, a, c, shared)
    }

    private fun resolveName(context: Context, raw: String): String {
        val uid: Int? = userApp.find(raw)?.let {
            it.groupValues[1].toInt() * 100000 + 10000 + it.groupValues[2].toInt()
        } ?: plainUid.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        if (uid == null) return raw
        when (uid) {
            0 -> return "Kernel"
            1000 -> return "Android system"
            1001 -> return "Phone and radio"
            2000 -> return "Shell"
        }
        val pm = context.packageManager
        val pkg = try { pm.getPackagesForUid(uid)?.firstOrNull() } catch (_: Exception) { null }
            ?: return raw
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }
}
