package com.aloys23.komiraquake.ui.warning

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import com.aloys23.komiraquake.ui.theme.AppSurfaces
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aloys23.komiraquake.KomiraApp
import com.aloys23.komiraquake.ui.components.WarningOverlay
import com.aloys23.komiraquake.ui.theme.KomiraTheme
import com.aloys23.komiraquake.ui.theme.resolveDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Bound to the notification's event identity, never to whichever event is selected later. */
class WarningActivity : ComponentActivity() {
    private val eventIdentity = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        eventIdentity.value = intent.getStringExtra(EXTRA_EVENT_ID)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eventIdentity.value = intent.getStringExtra(EXTRA_EVENT_ID)
        if (eventIdentity.value == null) { finish(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val container = (application as KomiraApp).container
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                container.collapseAlert(eventIdentity.value)
                finish()
            }
        })
        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            val dark = resolveDarkTheme(settings.themeMode, isSystemInDarkTheme())
            KomiraTheme(darkTheme = dark) {
                val warnings by container.repository.activeWarnings.collectAsState()
                val selected by container.repository.activeWarning.collectAsState()
                val overlayVisible by container.repository.warningOverlayVisible.collectAsState()
                val identity = eventIdentity.value
                val warning = warnings.firstOrNull { it.identity == identity }
                val seconds = androidx.compose.runtime.remember(identity) { mutableStateOf(-1) }
                LaunchedEffect(identity, selected?.identity, overlayVisible, settings.enableWarnings) {
                    // Selected B's visibility must never close a still-active A.
                    if (!settings.enableWarnings || (selected?.identity == identity && !overlayVisible)) finish()
                }
                LaunchedEffect(warning) {
                    if (warning == null || warning.isCanceled) {
                        finish()
                    } else {
                        while (isActive) {
                            seconds.value = if (warning.sWaveArrival == null) -1 else warning.remainingSeconds()
                            delay(250)
                        }
                    }
                }
                if (warning != null && !warning.isCanceled) {
                    Box(modifier = Modifier.fillMaxSize().background(AppSurfaces.surface(dark))) {
                        WarningOverlay(
                            event = warning,
                            dark = dark,
                            countdown = seconds.value,
                            reduceMotion = settings.reduceMotion,
                            standard = settings.intensityStandard,
                            onDismiss = { container.collapseAlert(identity); finish() },
                            onMute = { container.muteAlert(identity) },
                            onStop = { container.stopAlert(identity); finish() },
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
        const val EXTRA_EVENT_ID = "event_identity"
    }
}
