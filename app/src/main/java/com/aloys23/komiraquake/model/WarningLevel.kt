package com.aloys23.komiraquake.model

/**
 * 预警级别。code 与 《NATIVE_PORT_SPEC》 §1.2 一致，并作为 SQLite 的 warning_level 列存储。
 */
enum class WarningLevel(val code: Int, val tag: String, val displayName: String) {
    NORMAL(0, "NORMAL", "守候正常"),
    WATCH(1, "WATCH", "微震关注"),
    WARNING(2, "WARNING", "地震预警"),
    CRITICAL(3, "CRITICAL", "严重预警");

    val isAlert: Boolean get() = this == WARNING || this == CRITICAL

    companion object {
        fun fromCode(code: Int): WarningLevel = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}
