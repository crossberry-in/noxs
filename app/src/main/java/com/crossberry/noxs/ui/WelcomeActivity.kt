/*
 * Noxs — original implementation.
 * Welcome screen: entry point. Routes first-time users straight to the REAL
 * setup screen — the terminal console (SetupActivity / NoxsSetupSession)
 * that streams the actual Debian 12 provisioning — existing users to the
 * terminal, and never silently installs anything (spec §4).
 */
package com.crossberry.noxs.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityWelcomeBinding
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.runtime.NoxsService

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as com.crossberry.noxs.NoxsApplication
        // An environment is ready when a fresh install finished OR the legacy
        // Debian install of an upgrading user was imported (spec §9 migration:
        // existing data is kept, fresh installs use Ubuntu 24.04 LTS only).
        val ready = app.environments.environments.value.any { it.status == EnvironmentStatus.READY }

        binding.btnTerminal.isEnabled = ready
        binding.btnSetup.text = getString(
            if (ready) R.string.nav_settings else R.string.welcome_setup
        )

        binding.btnSetup.setOnClickListener {
            // No separate download/checklist screen: setup IS the real
            // terminal console (one environment — Ubuntu 24.04 LTS).
            startActivity(
                Intent(
                    this,
                    if (ready) SettingsActivity::class.java else SetupActivity::class.java
                )
            )
        }
        binding.btnTerminal.setOnClickListener {
            if (permissionOnboardingPending()) {
                // First-launch flow (Noxs API spec §9): Permission Center ->
                // verify -> Noxs Home. Runs once; afterwards the terminal
                // opens directly.
                NoxsService.start(this)
                startActivity(
                    Intent(this, PermissionCenterActivity::class.java)
                        .putExtra(PermissionCenterActivity.EXTRA_FIRST_RUN, true)
                )
                return@setOnClickListener
            }
            NoxsService.start(this)
            startActivity(Intent(this, TerminalActivity::class.java))
        }
    }

    private fun permissionOnboardingPending(): Boolean {
        val prefs = getSharedPreferences("noxs_settings", MODE_PRIVATE)
        if (prefs.getBoolean("permission_onboarding_done", false)) return false
        prefs.edit().putBoolean("permission_onboarding_done", true).apply()
        return true
    }
}
