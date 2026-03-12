/**
 * Diagnostic app for the Android Settings launch bug on devices with launchMode="singleInstance".
 *
 * On affected Android 15 devices, calling startActivityForResult() to open a Settings screen
 * silently fails when the calling activity has launchMode="singleInstance". No exception is
 * thrown; the activity pauses and resumes immediately with nothing shown.
 *
 * Mode 1 - Direct: opens Settings straight from a button tap. Baseline only.
 * Mode 2 - Dialog Flow: replicates a production call chain where the Settings launch
 *   is called from inside an AlertDialog button callback. This is the pattern that
 *   triggers the bug.
 *
 * Toggles:
 *   FLAG_ACTIVITY_NEW_TASK          — known workaround, not deployed (security risk)
 *   registerForActivityResult       — modern API alternative to startActivityForResult()
 *   startActivity (fire-and-forget) — drops result request; confirmed fix on affected devices
 *   Handler.post() wrap             — defers startActivityForResult() by one frame
 *   Trampoline activity             — routes the launch through a standard-task activity
 *
 * Logcat filter:
 *   adb logcat -v time SettingsDiagnostic:V ActivityTaskManager:V ActivityManager:V WindowManager:V *:S
 */

package com.imprivata.android.diagnostics

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // registerForActivityResult launcher — must be initialized before onCreate
    // Used when the "Use registerForActivityResult" toggle is on.
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        log("registerForActivityResult callback — resultCode=${result.resultCode}")
        Log.i(TAG, "[$TAG] registerForActivityResult callback resultCode=${result.resultCode}")
    }

    // Constants
    //--------------------------------------------------------------------------

    companion object {
        private const val TAG = "SettingsDiagnostic"

        private const val RC_ACCESSIBILITY         = 1001
        private const val RC_OVERLAY               = 1002
        private const val RC_NOTIFICATION_LISTENER = 1003
        private const val RC_NOTIFICATION_SETTINGS = 1004
        private const val RC_GENERAL_SETTINGS      = 1005
        private const val RC_DUMMY_ACTIVITY        = 1006

        /**
         * Pause/resume gaps shorter than this are flagged as the bug.
         * Observed bug timing is ~250ms; 500ms gives margin over normal transitions.
         */
        private const val QUICK_RESUME_THRESHOLD_MS = 500L
    }

    // Data: describes one permission to test in Mode 2
    //--------------------------------------------------------------------------

    private data class PermissionTest(
        val label: String,
        val action: String,
        val data: Uri? = null,
        val extras: Map<String, String> = emptyMap(),
        val requestCode: Int
    )

    // Mode 2 state machine
    //--------------------------------------------------------------------------

    /** IDLE: not running. DIALOG_SHOWING: waiting for user. SETTINGS_LAUNCHED: waiting for onResume. */
    private enum class FlowState { IDLE, DIALOG_SHOWING, SETTINGS_LAUNCHED }

    /** Permissions queued for the Mode 2 flow. Populated on Start, dequeued as each dialog is confirmed. */
    private val pendingPermissions = LinkedList<PermissionTest>()

    /** Current state of the Mode 2 flow. */
    private var flowState = FlowState.IDLE

    /** Label of the permission most recently sent to Settings (for timing log). */
    private var inFlightLabel = ""

    // Timing state (shared by both modes)
    //--------------------------------------------------------------------------

    /** Set in onPause(); cleared in onResume() after calculating elapsed time. */
    private var pauseTimestamp: Long = 0L

    /** Label of the last Settings action launched (for timing annotation). */
    private var lastLaunchedLabel: String = ""

    // Log buffer
    //--------------------------------------------------------------------------

    private val logBuffer = StringBuilder()

    // Views
    //--------------------------------------------------------------------------

    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var cbNewTask: CheckBox
    private lateinit var cbModernApi: CheckBox
    private lateinit var cbFireAndForget: CheckBox
    private lateinit var cbHandlerPost: CheckBox
    private lateinit var cbTrampoline: CheckBox
    private lateinit var cbSimAccessibility: CheckBox
    private lateinit var cbSimOverlay: CheckBox
    private lateinit var cbSimNotificationListener: CheckBox
    private lateinit var cbSimNotificationSettings: CheckBox
    private lateinit var btnStartFlow: Button
    private lateinit var btnStopFlow: Button

    // Lifecycle
    //--------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        displayDeviceInfo()
        wireButtons()

        log("MainActivity.onCreate() — ready")
        log("")
        log("Logcat filter:")
        log("  adb logcat -v time $TAG:V ActivityTaskManager:V ActivityManager:V WindowManager:V *:S")
        log("")
        log("Mode 1: tap a [Direct] button.")
        log("Mode 2: check permissions to simulate, tap 'Start Dialog Flow'.")
    }

    override fun onStart() {
        super.onStart()
        log("onStart() at ${nowMs()}ms")
    }

    override fun onResume() {
        super.onResume()
        val resumeTs = nowMs()

        // Timing result from the previous Settings launch.
        if (pauseTimestamp > 0L) {
            val elapsed = resumeTs - pauseTimestamp
            val verdict = if (elapsed < QUICK_RESUME_THRESHOLD_MS)
                "[BUG] Settings closed in only ${elapsed}ms"
            else
                "[OK] Settings was open for ${elapsed}ms"
            log("onResume() at ${resumeTs}ms")
            log(">>> onPause→onResume: ${elapsed}ms  $verdict")
            log(">>> Tested: $lastLaunchedLabel")
            pauseTimestamp = 0L
        } else {
            log("onResume() at ${resumeTs}ms")
        }

        // Mode 2: advance the dialog flow if running.
        // Only fire if waiting for Settings to return (SETTINGS_LAUNCHED).
        // If a dialog is already on screen, the dialog callback drives the next step.
        if (flowState == FlowState.SETTINGS_LAUNCHED) {
            log("[Flow] Returned from Settings — checking next permission")
            flowState = FlowState.IDLE   // reset before calling check (which may set DIALOG_SHOWING)
            checkNextSimulatedPermission()
        }
    }

    override fun onPause() {
        super.onPause()
        pauseTimestamp = nowMs()
        log("onPause() at ${pauseTimestamp}ms")
    }

    override fun onStop() {
        super.onStop()
        log("onStop() at ${nowMs()}ms")
    }

    override fun onDestroy() {
        super.onDestroy()
        log("onDestroy() at ${nowMs()}ms")
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val label = labelForRequestCode(requestCode)
        log("onActivityResult() — $label | resultCode=$resultCode")
        Log.i(TAG, "[$TAG] onActivityResult: $label resultCode=$resultCode data=$data" +
            " SDK=${Build.VERSION.SDK_INT} Device=${Build.MANUFACTURER} ${Build.MODEL}")
    }

    // Setup
    //--------------------------------------------------------------------------

    private fun bindViews() {
        tvDeviceInfo              = findViewById(R.id.tv_device_info)
        tvLog                     = findViewById(R.id.tv_log)
        cbNewTask                 = findViewById(R.id.cb_new_task)
        cbModernApi               = findViewById(R.id.cb_modern_api)
        cbFireAndForget           = findViewById(R.id.cb_fire_and_forget)
        cbHandlerPost             = findViewById(R.id.cb_handler_post)
        cbTrampoline              = findViewById(R.id.cb_trampoline)
        cbSimAccessibility        = findViewById(R.id.cb_sim_accessibility)
        cbSimOverlay              = findViewById(R.id.cb_sim_overlay)
        cbSimNotificationListener = findViewById(R.id.cb_sim_notification_listener)
        cbSimNotificationSettings = findViewById(R.id.cb_sim_notification_settings)
        btnStartFlow              = findViewById(R.id.btn_start_flow)
        btnStopFlow               = findViewById(R.id.btn_stop_flow)
    }

    private fun displayDeviceInfo() {
        val info = "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                   "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                   "Build: ${Build.DISPLAY}"
        tvDeviceInfo.text = info

        Log.i(TAG, "[$TAG] ==========================================")
        Log.i(TAG, "[$TAG] Android Settings Launch Diagnostic started")
        Log.i(TAG, "[$TAG] Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.i(TAG, "[$TAG] Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        Log.i(TAG, "[$TAG] Build: ${Build.DISPLAY}")
        Log.i(TAG, "[$TAG] ==========================================")
    }

    private fun wireButtons() {

        // Mode 1 buttons

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            launchSettingsDirect("Accessibility Settings",
                Settings.ACTION_ACCESSIBILITY_SETTINGS, requestCode = RC_ACCESSIBILITY)
        }

        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            launchSettingsDirect("Overlay Permission",
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                data = Uri.parse("package:$packageName"), requestCode = RC_OVERLAY)
        }

        findViewById<Button>(R.id.btn_notification_listener).setOnClickListener {
            launchSettingsDirect("Notification Listener Settings",
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                requestCode = RC_NOTIFICATION_LISTENER)
        }

        findViewById<Button>(R.id.btn_notification_settings).setOnClickListener {
            launchSettingsDirect("App Notification Settings",
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                extras = mapOf(Settings.EXTRA_APP_PACKAGE to packageName),
                requestCode = RC_NOTIFICATION_SETTINGS)
        }

        findViewById<Button>(R.id.btn_general_settings).setOnClickListener {
            launchSettingsDirect("General Settings (control)",
                Settings.ACTION_SETTINGS, requestCode = RC_GENERAL_SETTINGS)
        }

        findViewById<Button>(R.id.btn_dummy_activity).setOnClickListener {
            log("--- Mode 1: Direct ---")
            log("[Mode 1] In-App Activity (DummyTargetActivity)")
            lastLaunchedLabel = "[Mode 1] In-App Activity"
            val intent = Intent(this, DummyTargetActivity::class.java)
            if (cbNewTask.isChecked) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val apiNote = if (cbModernApi.isChecked) " [API=registerForActivityResult]"
                          else if (cbFireAndForget.isChecked) " [API=startActivity (fire-and-forget)]"
                          else if (cbHandlerPost.isChecked) " [API=startActivityForResult (Handler.post)]"
                          else if (cbTrampoline.isChecked) " [API=TrampolineActivity]"
                          else " [API=startActivityForResult]"
            val newTaskNote = if (cbNewTask.isChecked) " [FLAG_ACTIVITY_NEW_TASK=ON, workaround mode]"
                              else " [FLAG_ACTIVITY_NEW_TASK=OFF, bug reproduction mode]"
            log("  target=DummyTargetActivity$newTaskNote$apiNote")
            launchSettings(intent, RC_DUMMY_ACTIVITY)
        }

        // Mode 2 flow

        btnStartFlow.setOnClickListener {
            startDialogFlow()
        }

        btnStopFlow.setOnClickListener {
            stopDialogFlow()
        }

        // Utilities

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            logBuffer.clear()
            tvLog.text = ""
            log("Log cleared.")
            displayDeviceInfo()
        }

        findViewById<Button>(R.id.btn_copy).setOnClickListener {
            val content = logBuffer.toString()
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("SettingsDiagnosticLogs", content))
            Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
            log(">>> Copied ${content.lines().size} lines to clipboard")
        }
    }

    // Mode 1: direct launch
    //--------------------------------------------------------------------------

    /**
     * Launches Settings directly from a button tap. Baseline only.
     */
    @Suppress("DEPRECATION")
    private fun launchSettingsDirect(
        label: String,
        action: String,
        data: Uri? = null,
        extras: Map<String, String> = emptyMap(),
        requestCode: Int
    ) {
        log("--- Mode 1: Direct ---")
        log("[Mode 1] $label")
        lastLaunchedLabel = "[Mode 1] $label"

        val intent = buildIntent(action, data, extras)
        logIntent(intent)

        val resolveInfo = packageManager.resolveActivity(intent, 0)
        if (resolveInfo == null) {
            log("[WARNING] Cannot resolve intent — action may not exist on this device/OS")
            return
        }
        log("Resolves to: ${resolveInfo.activityInfo.packageName}")

        launchSettings(intent, requestCode)
    }

    // Mode 2: dialog flow
    //--------------------------------------------------------------------------

    /**
     * Builds the queue from the checkbox selections and kicks off the first dialog.
     */
    private fun startDialogFlow() {
        if (flowState != FlowState.IDLE) {
            log("[Flow] Already running — tap Stop Flow first")
            return
        }

        pendingPermissions.clear()

        if (cbSimAccessibility.isChecked) {
            pendingPermissions += PermissionTest(
                label       = "Accessibility Settings",
                action      = Settings.ACTION_ACCESSIBILITY_SETTINGS,
                requestCode = RC_ACCESSIBILITY
            )
        }
        if (cbSimOverlay.isChecked) {
            pendingPermissions += PermissionTest(
                label       = "Overlay Permission (Display Over Apps)",
                action      = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                data        = Uri.parse("package:$packageName"),
                requestCode = RC_OVERLAY
            )
        }
        if (cbSimNotificationListener.isChecked) {
            pendingPermissions += PermissionTest(
                label       = "Notification Listener Settings",
                action      = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                requestCode = RC_NOTIFICATION_LISTENER
            )
        }
        if (cbSimNotificationSettings.isChecked) {
            pendingPermissions += PermissionTest(
                label       = "App Notification Settings",
                action      = Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                extras      = mapOf(Settings.EXTRA_APP_PACKAGE to packageName),
                requestCode = RC_NOTIFICATION_SETTINGS
            )
        }

        if (pendingPermissions.isEmpty()) {
            log("[Flow] No permissions selected — check at least one box above")
            return
        }

        log("---")
        log("[Mode 2 — Dialog Flow] Starting with ${pendingPermissions.size} permission(s):")
        pendingPermissions.forEach { log("  - ${it.label}") }
        log("---")

        btnStartFlow.isEnabled = false
        btnStopFlow.isEnabled  = true

        checkNextSimulatedPermission()
    }

    /**
     * Shows the next permission dialog. Called from onResume() each time we return from Settings.
     */
    private fun checkNextSimulatedPermission() {
        if (flowState != FlowState.IDLE) return

        if (pendingPermissions.isEmpty()) {
            log("[OK] All simulated permissions processed — flow complete")
            log("---")
            stopDialogFlow()
            return
        }

        val next = pendingPermissions.peek()!!
        log("[Flow] Showing dialog for: ${next.label}")
        flowState = FlowState.DIALOG_SHOWING

        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(
                "Simulating: '${next.label}' is not granted.\n\n" +
                "Tap Enable to open Settings.\n\n" +
                "(This dialog represents a permission alert shown from onResume().)"
            )
            .setCancelable(false)
            .setPositiveButton("Enable") { dialog, _ ->
                dialog.dismiss()
                flowState = FlowState.IDLE

                val test = pendingPermissions.poll()!!
                log("[Flow] User tapped Enable for: ${test.label}")
                lastLaunchedLabel = "[Mode 2] ${test.label}"
                inFlightLabel = test.label

                val intent = buildIntent(test.action, test.data, test.extras)
                logIntent(intent)

                val resolveInfo = packageManager.resolveActivity(intent, 0)
                if (resolveInfo == null) {
                    log("[WARNING] Cannot resolve intent for ${test.label} — skipping")
                    flowState = FlowState.IDLE
                    checkNextSimulatedPermission()
                    return@setPositiveButton
                }
                log("[Flow] About to call startActivityForResult() from dialog callback…")
                launchSettings(intent, test.requestCode)
                flowState = FlowState.SETTINGS_LAUNCHED
            }
            .setNegativeButton("Skip") { dialog, _ ->
                dialog.dismiss()
                flowState = FlowState.IDLE
                val skipped = pendingPermissions.poll()
                log("[Flow] Skipped: ${skipped?.label}")
                checkNextSimulatedPermission()
            }
            .show()
    }

    private fun stopDialogFlow() {
        pendingPermissions.clear()
        flowState = FlowState.IDLE
        btnStartFlow.isEnabled = true
        btnStopFlow.isEnabled  = false
        log("[Flow] Stopped.")
    }

    // Shared Settings launch
    //--------------------------------------------------------------------------

    private fun launchSettings(intent: Intent, requestCode: Int) {
        val before = nowMs()
        if (cbFireAndForget.isChecked) {
            try {
                startActivity(intent)
                log("startActivity() returned in ${nowMs() - before}ms — waiting for onPause…")
            } catch (e: android.content.ActivityNotFoundException) {
                log("[ERROR] ActivityNotFoundException: ${e.message}")
                Log.e(TAG, "[$TAG] ActivityNotFoundException", e)
                pauseTimestamp = 0L
                if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
            } catch (e: Exception) {
                log("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "[$TAG] Exception launching Settings", e)
                pauseTimestamp = 0L
                if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
            }
        } else if (cbModernApi.isChecked) {
            try {
                settingsLauncher.launch(intent)
                log("settingsLauncher.launch() returned in ${nowMs() - before}ms — waiting for onPause…")
            } catch (e: android.content.ActivityNotFoundException) {
                log("[ERROR] ActivityNotFoundException: ${e.message}")
                Log.e(TAG, "[$TAG] ActivityNotFoundException", e)
                pauseTimestamp = 0L
                if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
            } catch (e: Exception) {
                log("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "[$TAG] Exception launching Settings", e)
                pauseTimestamp = 0L
                if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
            }
        } else if (cbHandlerPost.isChecked) {
            log("Handler.post() — deferring startActivityForResult() to next frame…")
            Handler(Looper.getMainLooper()).post {
                val beforePost = nowMs()
                @Suppress("DEPRECATION")
                try {
                    startActivityForResult(intent, requestCode)
                    log("startActivityForResult() [via Handler.post] returned in ${nowMs() - beforePost}ms — waiting for onPause…")
                } catch (e: android.content.ActivityNotFoundException) {
                    log("[ERROR] ActivityNotFoundException: ${e.message}")
                    Log.e(TAG, "[$TAG] ActivityNotFoundException", e)
                    pauseTimestamp = 0L
                    if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
                } catch (e: SecurityException) {
                    log("[ERROR] SecurityException: ${e.message}")
                    Log.e(TAG, "[$TAG] SecurityException", e)
                    pauseTimestamp = 0L
                    if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
                } catch (e: Exception) {
                    log("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "[$TAG] Exception launching Settings", e)
                    pauseTimestamp = 0L
                    if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
                }
            }
        } else if (cbTrampoline.isChecked) {
            try {
                val trampolineIntent = Intent(this, TrampolineActivity::class.java)
                    .putExtra(TrampolineActivity.EXTRA_SETTINGS_INTENT, intent)
                @Suppress("DEPRECATION")
                startActivityForResult(trampolineIntent, requestCode)
                log("startActivityForResult(TrampolineActivity) returned in ${nowMs() - before}ms — waiting for onPause…")
            } catch (e: Exception) {
                log("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "[$TAG] Exception launching Trampoline", e)
                pauseTimestamp = 0L
                if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                startActivityForResult(intent, requestCode)
                log("startActivityForResult() returned in ${nowMs() - before}ms — waiting for onPause…")
            } catch (e: android.content.ActivityNotFoundException) {
                log("[ERROR] ActivityNotFoundException: ${e.message}")
                Log.e(TAG, "[$TAG] ActivityNotFoundException", e)
                pauseTimestamp = 0L
                if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
            } catch (e: SecurityException) {
                log("[ERROR] SecurityException: ${e.message}")
                Log.e(TAG, "[$TAG] SecurityException", e)
                pauseTimestamp = 0L
                if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
            } catch (e: Exception) {
                log("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "[$TAG] Exception launching Settings", e)
                pauseTimestamp = 0L
                if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
            }
        }
    }

    // Intent helpers
    //--------------------------------------------------------------------------

    private fun buildIntent(
        action: String,
        data: Uri? = null,
        extras: Map<String, String> = emptyMap()
    ): Intent = Intent(action).also { i ->
        data?.let { i.data = it }
        extras.forEach { (k, v) -> i.putExtra(k, v) }
        if (cbNewTask.isChecked) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun logIntent(intent: Intent) {
        val newTaskNote = if (cbNewTask.isChecked) " [FLAG_ACTIVITY_NEW_TASK=ON, workaround mode]"
                          else " [FLAG_ACTIVITY_NEW_TASK=OFF, bug reproduction mode]"
        val apiNote = if (cbModernApi.isChecked) " [API=registerForActivityResult]"
                      else if (cbFireAndForget.isChecked) " [API=startActivity (fire-and-forget)]"
                      else if (cbHandlerPost.isChecked) " [API=startActivityForResult (Handler.post)]"
                      else if (cbTrampoline.isChecked) " [API=TrampolineActivity]"
                      else " [API=startActivityForResult]"
        log("  action=${intent.action}")
        log("  data=${intent.data ?: "(none)"}$newTaskNote$apiNote")
    }

    // Logging
    //--------------------------------------------------------------------------

    private fun log(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] $message"
        Log.i(TAG, "[$TAG] $message")
        logBuffer.appendLine(line)
        runOnUiThread {
            tvLog.text = logBuffer.toString()
            val sv = findViewById<android.widget.ScrollView>(R.id.scroll_view_log)
            sv?.post { sv.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
        }
    }

    // Utility
    //--------------------------------------------------------------------------

    private fun nowMs() = System.currentTimeMillis()

    private fun labelForRequestCode(rc: Int) = when (rc) {
        RC_ACCESSIBILITY         -> "Accessibility Settings"
        RC_OVERLAY               -> "Overlay Permission"
        RC_NOTIFICATION_LISTENER -> "Notification Listener"
        RC_NOTIFICATION_SETTINGS -> "App Notification Settings"
        RC_GENERAL_SETTINGS      -> "General Settings"
        RC_DUMMY_ACTIVITY        -> "In-App Activity (DummyTargetActivity)"
        else                     -> "Unknown (rc=$rc)"
    }
}