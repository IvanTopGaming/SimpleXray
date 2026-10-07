package com.simplexray.an.service.runtime

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.simplexray.an.service.TProxyService.Companion.ACTION_LOG_UPDATE
import com.simplexray.an.service.TProxyService.Companion.EXTRA_LOG_DATA

internal class ServiceLogBroadcaster(private val service: Service) {
    private val handler = Handler(Looper.getMainLooper())
    private val logBroadcastBuffer: MutableList<String> = mutableListOf()
    private val broadcastLogsRunnable = Runnable {
        synchronized(logBroadcastBuffer) {
            if (logBroadcastBuffer.isNotEmpty()) {
                val logUpdateIntent = Intent(ACTION_LOG_UPDATE)
                logUpdateIntent.setPackage(service.application.packageName)
                logUpdateIntent.putStringArrayListExtra(
                    EXTRA_LOG_DATA,
                    ArrayList(logBroadcastBuffer),
                )
                service.sendBroadcast(logUpdateIntent)
                logBroadcastBuffer.clear()
                Log.d(TAG, "Broadcasted a batch of logs.")
            }
        }
    }

    fun append(line: String) {
        synchronized(logBroadcastBuffer) {
            logBroadcastBuffer.add(line)
            if (!handler.hasCallbacks(broadcastLogsRunnable)) {
                handler.postDelayed(broadcastLogsRunnable, BROADCAST_DELAY_MS)
            }
        }
    }

    fun flush() {
        handler.removeCallbacks(broadcastLogsRunnable)
        broadcastLogsRunnable.run()
    }

    private companion object {
        const val TAG = "VpnService"
        const val BROADCAST_DELAY_MS: Long = 3000
    }
}
