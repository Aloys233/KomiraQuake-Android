package com.aloys23.komiraquake

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
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

        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            val dark = resolveDarkTheme(settings.themeMode, isSystemInDarkTheme())
            KomiraTheme(darkTheme = dark) {
                QuakeApp(container = container)
            }
        }
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
