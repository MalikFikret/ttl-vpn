package com.malikfikret.ttlvpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.malikfikret.ttlvpn.ui.theme.TTLVPNTheme
import ttlvpn.Ttlvpn

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Smoke test: forces the Go native library to load.
        // stop() is a no-op when the engine is not running.
        val engineStatus = try {
            Ttlvpn.stop()
            "Engine loaded"
        } catch (e: Throwable) {
            "Engine failed: ${e.message}"
        }
        setContent {
            TTLVPNTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = engineStatus,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    TTLVPNTheme {
        Greeting("Android")
    }
}