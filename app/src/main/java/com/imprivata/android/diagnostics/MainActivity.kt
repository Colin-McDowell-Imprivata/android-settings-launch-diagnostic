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

    // Must be initialized before onCreate.
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

        // Observed bug timing ~250ms; 500ms gives comfortable margin.
        private const val QUICK_RESUME_THRESHOLD_MS = 500L
    }

    // State
    //--------------------------------------------------------------------------

    private data class PermissionTest(
        val label: String,
        val action: String,
        val data: Uri? = null,
        val extras: Map<String, String> = emptyMap(),
        val requestCode: Int
    )

    private enum class FlowState { IDLE, DIALOG_SHOWING, SETTINGS_LAUNCHED }

    private val pendingPermissions = LinkedList<PermissionTest>()
    private var flowState = FlowState.IDLE
    private var inFlightLabel = ""

    private var pauseTimestamp: Long = 0L
    private var lastLaunchedLabel: String = ""

    private val logBuffer = StringBuilder()

    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var cbNewTask: CheckBox
    private lateinit var cbModernApi: CheckBox
    private lateinit var cbFireAndForget: CheckBox
    private lateinit var cbHandlerPost: CheckBox
    private lateinit var cbTrampoline: CheckBox
    private lateinit var cbDefaultApi: CheckBox
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

        // Advance Mode 2 flow if we're returning from a Settings launch.
        if (flowState == FlowState.SETTINGS_LAUNCHED) {
            log("[Flow] Returned from Settings — checking next permission")
            flowState = FlowState.IDLE
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
        cbDefaultApi              = findViewById(R.id.cb_default_api)
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

        // API method options are mutually exclusive; Handler.post and NEW_TASK are independent modifiers.
        val exclusiveApiBoxes = listOf(cbDefaultApi, cbModernApi, cbFireAndForget, cbTrampoline)
        exclusiveApiBoxes.forEach { box ->
            box.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    exclusiveApiBoxes.forEach { other -> if (other !== box) other.isChecked = false }
                } else if (exclusiveApiBoxes.none { it.isChecked }) {
                    box.isChecked = true
                }
            }
        }

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
            val api = when {
                cbModernApi.isChecked    -> "registerForActivityResult"
                cbFireAndForget.isChecked -> "startActivity (no result)"
                cbTrampoline.isChecked   -> "TrampolineActivity"
                else                     -> "startActivityForResult"
            }
            val newTaskNote = if (cbNewTask.isChecked)    " [FLAG_ACTIVITY_NEW_TASK=ON]" else ""
            val handlerNote = if (cbHandlerPost.isChecked) " [Handler.post=ON]" else ""
            log("  target=DummyTargetActivity  API=$api$handlerNote$newTaskNote")
            launchSettings(intent, RC_DUMMY_ACTIVITY)
        }

        btnStartFlow.setOnClickListener {
            startDialogFlow()
        }

        btnStopFlow.setOnClickListener {
            stopDialogFlow()
        }

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

    // Mode 1 — Direct launch
    //--------------------------------------------------------------------------

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

    // Mode 2 — Dialog flow
    //--------------------------------------------------------------------------

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

    // Settings launch
    //--------------------------------------------------------------------------

    private fun launchSettings(intent: Intent, requestCode: Int) {
        if (cbHandlerPost.isChecked) {
            log("Handler.post() — deferring launch to next frame…")
            Handler(Looper.getMainLooper()).post { doLaunch(intent, requestCode, deferred = true) }
        } else {
            doLaunch(intent, requestCode, deferred = false)
        }
    }

    private fun doLaunch(intent: Intent, requestCode: Int, deferred: Boolean) {
        val before = nowMs()
        val deferSuffix = if (deferred) " [via Handler.post]" else ""

        fun onError(label: String, e: Exception) {
            log("[ERROR] $label: ${e.message}")
            Log.e(TAG, "[$TAG] $label", e)
            pauseTimestamp = 0L
            if (flowState == FlowState.SETTINGS_LAUNCHED) { flowState = FlowState.IDLE; checkNextSimulatedPermission() }
        }

        if (cbFireAndForget.isChecked) {
            try {
                startActivity(intent)
                log("startActivity()$deferSuffix returned in ${nowMs() - before}ms — waiting for onPause…")
            } catch (e: android.content.ActivityNotFoundException) { onError("ActivityNotFoundException", e) }
              catch (e: Exception) { onError(e.javaClass.simpleName, e) }
        } else if (cbModernApi.isChecked) {
            try {
                settingsLauncher.launch(intent)
                log("settingsLauncher.launch()$deferSuffix returned in ${nowMs() - before}ms — waiting for onPause…")
            } catch (e: android.content.ActivityNotFoundException) { onError("ActivityNotFoundException", e) }
              catch (e: Exception) { onError(e.javaClass.simpleName, e) }
        } else if (cbTrampoline.isChecked) {
            try {
                val trampolineIntent = Intent(this, TrampolineActivity::class.java)
                    .putExtra(TrampolineActivity.EXTRA_SETTINGS_INTENT, intent)
                @Suppress("DEPRECATION")
                startActivityForResult(trampolineIntent, requestCode)
                log("startActivityForResult(TrampolineActivity)$deferSuffix returned in ${nowMs() - before}ms — waiting for onPause…")
            } catch (e: Exception) { onError(e.javaClass.simpleName, e) }
        } else {
            // Default — startActivityForResult()
            @Suppress("DEPRECATION")
            try {
                startActivityForResult(intent, requestCode)
                log("startActivityForResult()$deferSuffix returned in ${nowMs() - before}ms — waiting for onPause…")
            } catch (e: android.content.ActivityNotFoundException) { onError("ActivityNotFoundException", e) }
              catch (e: SecurityException) { onError("SecurityException", e) }
              catch (e: Exception) { onError(e.javaClass.simpleName, e) }
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
        val api = when {
            cbModernApi.isChecked    -> "registerForActivityResult"
            cbFireAndForget.isChecked -> "startActivity (no result)"
            cbTrampoline.isChecked   -> "TrampolineActivity"
            else                     -> "startActivityForResult"
        }
        val newTaskNote  = if (cbNewTask.isChecked)    " [FLAG_ACTIVITY_NEW_TASK=ON]" else ""
        val handlerNote  = if (cbHandlerPost.isChecked) " [Handler.post=ON]" else ""
        log("  action=${intent.action}")
        log("  data=${intent.data ?: "(none)"}  API=$api$handlerNote$newTaskNote")
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