package com.smartscan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.smartscan.app.ui.CameraScreen
import com.smartscan.app.ui.CropScreen
import com.smartscan.app.ui.EditorScreen
import com.smartscan.app.ui.HomeScreen
import com.smartscan.app.ui.SmartScanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SmartScanTheme { AppNavigation() } }
    }
}

@Composable
private fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpen = { nav.navigate("editor/$it") },
                onScan = { nav.navigate("camera/new") },
            )
        }
        composable("camera/{docId}") { entry ->
            val arg = entry.arguments?.getString("docId")
            CameraScreen(
                initialDocId = arg?.takeIf { it != "new" },
                onFinished = { docId, cropPageId ->
                    nav.navigate("editor/$docId") { popUpTo("home") }
                    if (cropPageId != null) nav.navigate("crop/$docId/$cropPageId")
                },
                onCancel = { nav.popBackStack() },
            )
        }
        composable("editor/{docId}") { entry ->
            val docId = entry.arguments?.getString("docId") ?: return@composable
            EditorScreen(
                docId = docId,
                onBack = { nav.popBackStack("home", inclusive = false) },
                onCrop = { pageId -> nav.navigate("crop/$docId/$pageId") },
                onAddPages = { nav.navigate("camera/$docId") },
            )
        }
        composable("crop/{docId}/{pageId}") { entry ->
            val docId = entry.arguments?.getString("docId") ?: return@composable
            val pageId = entry.arguments?.getString("pageId") ?: return@composable
            CropScreen(docId = docId, pageId = pageId, onDone = { nav.popBackStack() })
        }
    }
}
