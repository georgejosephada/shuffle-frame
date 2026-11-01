package com.gjnet.shuffleframe

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.View>(R.id.root).padForSystemBars()
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) supportFragmentManager.commit { replace(R.id.settings, Fragment()) }
    }

    class Fragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
            findPreference<Preference>("battery")?.setOnPreferenceClickListener { requestBatteryExemption(); true }
            findPreference<Preference>("full_screen")?.setOnPreferenceClickListener { openFullScreenSettings(); true }
            findPreference<Preference>("check_updates")?.setOnPreferenceClickListener {
                Updater.checkNow(requireContext())
                it.summary = getString(R.string.update_checking)
                true
            }
        }

        override fun onResume() {
            super.onResume()
            PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(this)
            val pm = requireContext().getSystemService(PowerManager::class.java)
            findPreference<Preference>("battery")?.summary = getString(
                if (pm.isIgnoringBatteryOptimizations(requireContext().packageName)) R.string.battery_ok else R.string.battery_summary
            )
            findPreference<Preference>("storage")?.summary = formatBytes(Store.storageBytes())
            val nm = requireContext().getSystemService(android.app.NotificationManager::class.java)
            findPreference<Preference>("full_screen")?.isVisible =
                !androidx.core.app.NotificationManagerCompat.from(requireContext()).areNotificationsEnabled() ||
                    (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent())
            findPreference<Preference>("version")?.summary =
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName +
                    " (build ${Updater.installedVersion(requireContext())})"
            findPreference<Preference>("check_updates")?.apply {
                isVisible = BuildConfig.UPDATE_REPO.isNotEmpty()
                summary = Updater.status(requireContext()) ?: getString(R.string.update_auto)
            }
        }

        override fun onPause() {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            if (key == "sync_interval" || key == "wifi_only") SyncScheduler.schedule(requireContext(), update = true)
            if (key == "frame_mode") FrameService.sync(requireContext())
            if (key == "update_status") findPreference<Preference>("check_updates")?.summary = Updater.status(requireContext())
        }

        @SuppressLint("BatteryLife")
        private fun requestBatteryExemption() {
            val ctx = requireContext()
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        private fun openFullScreenSettings() {
            val ctx = requireContext()
            try {
                val intent = Setup.missing(ctx).firstOrNull()?.intent
                    ?: Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        }
    }
}
