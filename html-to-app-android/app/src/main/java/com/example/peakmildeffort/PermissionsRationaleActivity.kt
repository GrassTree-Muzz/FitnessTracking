package com.example.peakmildeffort

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Health Connect opens this when you tap the privacy link on its permission screen. */
class PermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setText(R.string.health_privacy)
            textSize = 16f
            setLineSpacing(0f, 1.3f)
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(text)
        })
    }
}
