package com.aloys23.komiraquake

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aloys23.komiraquake.ui.QuakeApp
import com.aloys23.komiraquake.ui.theme.KomiraTheme
import com.aloys23.komiraquake.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {

    private var hadLocationPermission = false
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissions()
        }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        val granted = com.aloys23.komiraquake.service.SystemPermissions.hasLocationPermission(this)
        val container = (application as KomiraApp).container
        if (granted && !hadLocationPermission &&
            container.location.state.value.source != com.aloys23.komiraquake.service.LocationSource.MANUAL) {
            container.location.requestCurrentPosition()
        }
        hadLocationPermission = granted
        if (!container.dnd.hasAccess()) container.dnd.release()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()

        val container = (application as KomiraApp).container

        // 全屏沉浸：内容铺到系统栏之下，系统栏透明、图标明暗随主题。
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        applyEdgeToEdge(resolveDarkTheme(container.settings.current.themeMode, systemDark))

        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            val dark = resolveDarkTheme(settings.themeMode, isSystemInDarkTheme())
            LaunchedEffect(dark) { applyEdgeToEdge(dark) }
            KomiraTheme(darkTheme = dark) {
                QuakeApp(container = container)
            }
        }
    }

    /** Transparent system bars with icon contrast matched to [dark]; no navigation-bar scrim. */
    private fun applyEdgeToEdge(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (dark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            },
            navigationBarStyle = if (dark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            },
        )
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf<String>()
        if (!com.aloys23.komiraquake.service.SystemPermissions.hasLocationPermission(this)) {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
            wanted += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }
}
