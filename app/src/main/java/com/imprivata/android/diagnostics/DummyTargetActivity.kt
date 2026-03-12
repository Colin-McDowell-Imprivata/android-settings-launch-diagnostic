package com.imprivata.android.diagnostics

import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** In-app target for testing whether the bug affects all result launches or only system activities. */
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
