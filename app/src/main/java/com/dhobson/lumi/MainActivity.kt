package com.dhobson.lumi

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dhobson.lumi.ui.LumiTheme
import com.dhobson.lumi.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val session: Session get() = (application as LumiApp).session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark whatever the system setting, so the system bars' icons must be light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Playing the hardware keyboard does not touch the screen, so stop it sleeping.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            LumiTheme {
                MainScreen(session)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        session.onActivityStarted()
    }

    override fun onStop() {
        session.onActivityStopped()
        super.onStop()
    }
}
