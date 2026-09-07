package com.example.batteryrestrict

import com.topjohnwu.superuser.Shell

/**
 * Thin wrapper around libsu for writing/reading the qcom-battery sysfs nodes.
 * All calls run a root shell command, so they only work on a rooted device
 * with these nodes actually present in the kernel.
 */
object RootUtils {

    private const val RESTRICT_CHG = "/sys/class/qcom-battery/restrict_chg"
    private const val RESTRICT_CUR = "/sys/class/qcom-battery/restrict_cur"

    fun hasRoot(): Boolean = Shell.getShell().isRoot

    fun nodeExists(path: String): Boolean {
        val result = Shell.cmd("[ -e $path ] && echo yes || echo no").exec()
        return result.isSuccess && result.out.joinToString("").trim() == "yes"
    }

    fun setRestrictChg(enable: Boolean): Boolean {
        val value = if (enable) "1" else "0"
        return Shell.cmd("echo $value > $RESTRICT_CHG").exec().isSuccess
    }

    fun setRestrictCur(milliamps: Int): Boolean {
        return Shell.cmd("echo $milliamps > $RESTRICT_CUR").exec().isSuccess
    }

    fun readRestrictChg(): String = readValue(RESTRICT_CHG)

    fun readRestrictCur(): String = readValue(RESTRICT_CUR)

    private fun readValue(path: String): String {
        val result = Shell.cmd("cat $path").exec()
        return if (result.isSuccess) result.out.joinToString("\n").trim() else ""
    }
}
