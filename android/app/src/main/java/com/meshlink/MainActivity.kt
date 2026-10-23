package com.meshlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    private val mesh: MeshManager get() = (application as MeshApp).mesh

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MeshTheme { App(mesh) } }
    }

    override fun onStart() {
        super.onStart()
        mesh.appVisible = true
    }

    override fun onStop() {
        mesh.appVisible = false
        super.onStop()
    }
}
