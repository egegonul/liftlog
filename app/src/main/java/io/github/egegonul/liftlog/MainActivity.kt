package io.github.egegonul.liftlog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store(applicationContext)
        setContent { LiftTheme { App(store) } }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FAD),
    secondary = Color(0xFFC22A2A),
    background = Color(0xFFEEF0EE),
    surface = Color(0xFFEEF0EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FAEE8),
    secondary = Color(0xFFE26A6A),
    background = Color(0xFF141917),
    surface = Color(0xFF141917),
)

@Composable
fun LiftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
