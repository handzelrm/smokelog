package com.smokelog.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smokelog.SmokeLogApp
import com.smokelog.share.CookShare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Cook id to open after importing a shared file. */
    private val openCook = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleShare(intent)
        setContent { SmokeLogTheme { AppNav(openCook) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        lifecycleScope.launch {
            try {
                openCook.value = CookShare.import(this@MainActivity, uri, SmokeLogApp.instance.db.cookDao())
                Toast.makeText(this@MainActivity, "Imported shared cook", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Couldn't import: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun AppNav(openCook: MutableStateFlow<Long?>) {
    val nav = rememberNavController()
    val pending by openCook.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { nav.navigate("cook/$it"); openCook.value = null }
    }
    NavHost(nav, startDestination = "list") {
        composable("list") {
            CookListScreen(
                onOpen = { nav.navigate("cook/$it") },
                onNew = { id -> nav.navigate("cook/$id"); nav.navigate("edit/$id") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("cook/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            val vm: CookDetailViewModel = viewModel(key = "detail$id") { CookDetailViewModel(id) }
            CookDetailScreen(vm, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("edit/$id") })
        }
        composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            val vm: CookEditViewModel = viewModel(key = "edit$id") { CookEditViewModel(id) }
            CookEditScreen(vm, onDone = { nav.popBackStack() })
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
