package com.simplexray.an.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import com.simplexray.an.prefs.Preferences
import java.io.File

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val prefs = Preferences(context)
            if (!prefs.autoStartEnabled) return
            val selected = prefs.selectedConfigPath ?: return
            if (!File(selected).isFile) return
            if (!prefs.disableVpn && VpnService.prepare(context) != null) {
                Log.i("BootReceiver", "Automatic connection requires VPN consent")
                return
            }
            val action =
                if (prefs.disableVpn) TProxyService.ACTION_START else TProxyService.ACTION_CONNECT
            ContextCompat.startForegroundService(
                context,
                Intent(context, TProxyService::class.java).setAction(action),
            )
        } catch (error: IllegalStateException) {
            Log.e("BootReceiver", "Automatic connection was not started", error)
        } catch (error: SecurityException) {
            Log.e("BootReceiver", "Automatic connection was denied", error)
        }
    }
}
