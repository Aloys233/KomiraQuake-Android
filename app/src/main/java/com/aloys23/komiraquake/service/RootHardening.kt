package com.aloys23.komiraquake.service

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * 有 root 时的「一键加固」：执行固定命令集（无任何用户输入拼接），把应用加入电池优化
 * 白名单、逃逸 App Standby、预授予运行时权限。《NATIVE_PORT_SPEC》 §15。
 */
object RootHardening {

    fun isRootAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        if (!process.waitFor(ROOT_PROBE_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroy()
            false
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.exitValue() == 0 && output.contains("uid=0")
        }
    }.getOrDefault(false)

    /** 返回逐行执行结果，供 UI 展示。调用方需在 IO 线程执行。 */
    fun apply(context: Context): String {
        val pkg = context.packageName
        val commands = listOf(
            "dumpsys deviceidle whitelist +$pkg",
            "am set-standby-bucket $pkg active",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "pm grant $pkg android.permission.ACCESS_FINE_LOCATION",
            "pm grant $pkg android.permission.ACCESS_COARSE_LOCATION",
        )
        return commands.joinToString(separator = "\n") { command ->
            "$ ${command}\n${runSu(command)}"
        }
    }

    private fun runSu(command: String): String = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching "(超时)"
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (output.isEmpty()) "(exit ${process.exitValue()})" else output
    }.getOrDefault("(执行失败)")

    private const val ROOT_PROBE_TIMEOUT_S = 5L
    private const val COMMAND_TIMEOUT_S = 10L
}
