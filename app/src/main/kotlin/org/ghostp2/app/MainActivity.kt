package org.ghostp2.app

import android.os.Bundle
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Impide capturas de pantalla y miniaturas en recientes
        window.setFlags(FLAG_SECURE, FLAG_SECURE)
        setContent {
            MaterialTheme {
                Text("GhostP2")
            }
        }
    }
}
