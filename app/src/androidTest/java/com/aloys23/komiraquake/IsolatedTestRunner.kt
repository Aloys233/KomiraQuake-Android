package com.aloys23.komiraquake

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Tests never start KomiraApp's live feeds, foreground guard, or alert pipeline. */
class IsolatedTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
