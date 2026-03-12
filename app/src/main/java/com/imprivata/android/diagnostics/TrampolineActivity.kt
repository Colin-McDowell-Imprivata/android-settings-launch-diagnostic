package com.imprivata.android.diagnostics

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent trampoline activity with a standard task affinity. Routes a Settings launch
 * through a non-singleInstance task, then finishes to return control to MainActivity.
 *
 * Flow: MainActivity → startActivity(TrampolineActivity) → startActivityForResult(Settings)
 *       → onActivityResult() → setResult() + finish() → MainActivity.onResume()
 */
class TrampolineActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsDiagnostic"
        private const val RC_SETTINGS = 2001
        const val EXTRA_SETTINGS_INTENT = "settings_intent"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_SETTINGS_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_SETTINGS_INTENT)
        }

        if (settingsIntent == null) {
            Log.e(TAG, "[$TAG] [Trampoline] No settings intent received — finishing")
            finish()
            return
        }

        Log.i(TAG, "[$TAG] [Trampoline] Launching from normal task: ${settingsIntent.action}")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(settingsIntent, RC_SETTINGS)
        } catch (e: Exception) {
            Log.e(TAG, "[$TAG] [Trampoline] Exception: ${e.message}")
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "[$TAG] [Trampoline] onActivityResult resultCode=$resultCode")
        setResult(resultCode, data)
        finish()
    }
}
