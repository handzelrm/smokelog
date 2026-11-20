package com.smokelog.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Hands a prompt to the Claude Android app installed on this phone (uses the person's
 * Claude subscription; no API key). The Claude app can't return an answer to another
 * app, so the reply comes back by copy → "Paste Claude's reply" in SmokeLog.
 */
object ClaudeApp {
    const val PACKAGE = "com.anthropic.claude"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    /** Opens the Claude app with [prompt] ready to send. Returns false if it isn't installed. */
    fun open(context: Context, prompt: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND)
            .setPackage(PACKAGE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, prompt)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); true } catch (e: Exception) { false }
    }
}
