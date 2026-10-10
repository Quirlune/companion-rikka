package me.rerere.rikkahub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.gray.companion.MainActivity

/** Retains the old internal component name without reviving the retired chat runtime. */
class RouteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Legacy Room conversation identifiers and share payloads have no Companion mapping.
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}
