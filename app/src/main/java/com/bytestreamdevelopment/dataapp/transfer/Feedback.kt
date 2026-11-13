package com.bytestreamdevelopment.dataapp.transfer

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.bytestreamdevelopment.dataapp.storage.AppPrefs

/** Spürbare/hörbare Rückmeldung: Handys haben sich erkannt, Übertragung fertig oder fehlgeschlagen. */
object Feedback {
    private val main = Handler(Looper.getMainLooper())

    /** Kurzes Tippen: die Handys haben sich gefunden. */
    fun detected(context: Context, prefs: AppPrefs) {
        if (prefs.feedback) vibrate(context, VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Doppeltes Tippen + kurzer Ton: fertig. */
    fun success(context: Context, prefs: AppPrefs) {
        if (!prefs.feedback) return
        vibrate(context, VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60), -1))
        tone(ToneGenerator.TONE_PROP_ACK)
    }

    /** Langes Brummen: abgebrochen oder fehlgeschlagen. */
    fun failure(context: Context, prefs: AppPrefs) {
        if (prefs.feedback) vibrate(context, VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrate(context: Context, effect: VibrationEffect) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(effect)
        }
    }

    private fun tone(type: Int) {
        main.post {
            runCatching {
                val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
                generator.startTone(type, 200)
                main.postDelayed({ runCatching { generator.release() } }, 600)
            }
        }
    }
}
