package com.aloys23.komiraquake.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aloys23.komiraquake.data.prefs.SettingsStore

/** 开机 / 应用更新后，若「后台保活服务」开启则拉起 [GuardService]。《NATIVE_PORT_SPEC》 §15。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            -> if (SettingsStore(context).current.enableBackgroundGuard) GuardService.start(context)
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
