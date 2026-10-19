package com.lagradost.cloudstream4.desktop.plugins

import androidx.appcompat.app.AppCompatActivity

/** The single activity desktop extensions see, both as their load context and as CommonActivity.activity. */
object DesktopActivity {
    val instance: AppCompatActivity by lazy { AppCompatActivity() }
}
