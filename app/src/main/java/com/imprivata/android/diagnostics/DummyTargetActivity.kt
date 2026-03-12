package com.imprivata.android.diagnostics

import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal in-app target activity used to test whether the singleInstance
 * startActivityForResult() bug is specific to system/Settings activities or
 * affects any result-requesting launch from a singleInstance task.
 *
 * Press Back (or the system back gesture) to return a result to MainActivity.
 */
class DummyTargetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this).apply {
            text = "DummyTargetActivity\n\nThis is an in-app target.\n\nPress Back to return to MainActivity."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        setContentView(tv)
    }
}
