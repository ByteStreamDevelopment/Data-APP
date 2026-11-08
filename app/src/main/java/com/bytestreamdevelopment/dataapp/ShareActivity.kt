package com.bytestreamdevelopment.dataapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.bytestreamdevelopment.dataapp.transfer.ReceiverState
import com.bytestreamdevelopment.dataapp.ui.DataTheme
import com.bytestreamdevelopment.dataapp.ui.SendScreen
import com.bytestreamdevelopment.dataapp.ui.SendViewModel

/** Ziel des System-„Teilen“-Menüs: sucht das Handy in der Nähe und sendet die geteilten Dateien. */
class ShareActivity : ComponentActivity() {
    private val vm: SendViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.load(intent)
        setContent { DataTheme { SendScreen(vm, onClose = ::finish) } }
    }

    // Solange gesendet wird, pausiert der Hintergrund-Kontaktaustausch auf diesem Handy.
    override fun onStart() {
        super.onStart()
        ReceiverState.setShareActive(true)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) ReceiverState.setShareActive(false)
    }
}
