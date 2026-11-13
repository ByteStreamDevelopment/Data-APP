package com.bytestreamdevelopment.dataapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bytestreamdevelopment.dataapp.storage.AppPrefs
import com.bytestreamdevelopment.dataapp.transfer.Bluetooth
import com.bytestreamdevelopment.dataapp.transfer.ReceiverService
import kotlin.concurrent.thread

/** Schaltet die Empfangsbereitschaft nach dem Neustart des Handys wieder ein (falls in den Einstellungen gewählt). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        if (!AppPrefs(app).autoStartOnBoot || !Bluetooth.hasPermissions(app)) return
        val pending = goAsync()
        thread {
            try {
                // Bluetooth braucht nach dem Hochfahren oft noch einen Moment.
                repeat(16) {
                    if (Bluetooth.isEnabled(app)) return@repeat
                    Thread.sleep(500)
                }
                if (Bluetooth.isEnabled(app)) ReceiverService.start(app)
            } catch (e: Exception) {
                Log.w("BootReceiver", "Autostart fehlgeschlagen", e)
            } finally {
                pending.finish()
            }
        }
    }
}
