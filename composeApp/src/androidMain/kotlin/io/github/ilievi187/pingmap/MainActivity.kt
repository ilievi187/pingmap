package io.github.ilievi187.pingmap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ilievi187.pingmap.net.AndroidAppContext
import io.github.ilievi187.pingmap.ui.PingMapApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAppContext.appContext = applicationContext
        enableEdgeToEdge()
        setContent {
            PingMapApp()
        }
    }
}
