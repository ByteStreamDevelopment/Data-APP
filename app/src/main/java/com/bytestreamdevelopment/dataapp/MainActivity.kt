package com.bytestreamdevelopment.dataapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bytestreamdevelopment.dataapp.ui.DataTheme
import com.bytestreamdevelopment.dataapp.ui.MainScreen

/** Startbildschirm: Empfangsbereitschaft und Liste der empfangenen Dateien. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DataTheme { MainScreen() } }
    }
}
