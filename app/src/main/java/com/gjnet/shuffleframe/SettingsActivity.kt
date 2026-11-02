package com.gjnet.shuffleframe

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.View>(R.id.root).padForSystemBars()
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) supportFragmentManager.commit { replace(R.id.settings, Fragment()) }
    }

    class Fragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val app get() = requireActivity().application as App

        private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext().applicationContext
            app.scope.launch {
                val ok = runCatching {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(AlbumBackup.toJson(Store.albums()).toByteArray()) }
                }.isSuccess
                toast(ctx, ctx.getString(if (ok) R.string.backup_saved else R.string.backup_failed, Store.albums().size))
            }
        }

        private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext().applicationContext
            toast(ctx, ctx.getString(R.string.restore_started))
            app.scope.launch {
                val message = try {
                    val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    val r = AlbumBackup.restore(ctx, AlbumBackup.parse(text))
                    ctx.getString(R.string.restore_done, r.added, r.alreadyThere) +
                        if (r.failed.isEmpty()) "" else "\n" + ctx.getString(R.string.restore_failed_some, r.failed.joinToString())
                } catch (e: Exception) {
                    ctx.getString(R.string.restore_failed, e.message ?: e.javaClass.simpleName)
                }
                toast(ctx, message)
            }
        }

        private fun toast(ctx: android.content.Context, text: String) = Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
            findPreference<Preference>("battery")?.setOnPreferenceClickListener { requestBatteryExemption(); true }
            findPreference<Preference>("full_screen")?.setOnPreferenceClickListener { openFullScreenSettings(); true }
            findPreference<Preference>("backup_save")?.setOnPreferenceClickListener {
                saveBackup.launch("shuffle-frame-albums-${java.time.LocalDate.now()}.json")
                true
            }
            findPreference<Preference>("backup_restore")?.setOnPreferenceClickListener {
                openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                true
            }
            confirmQualityChange("video_quality", R.array.video_quality_lower, R.array.video_quality_higher)
            confirmQualityChange("photo_format", R.array.photo_format_lower, R.array.photo_format_higher)
            findPreference<Preference>("check_updates")?.setOnPreferenceClickListener {
                Updater.checkNow(requireContext())
                it.summary = getString(R.string.update_checking)
                true
            }
        }

        /**
         * Changing what's stored replaces files already on the tablet, so ask first: lower quality
         * can only be undone by downloading everything again, higher quality costs space and data.
         * The option lists run from lowest to highest quality; [lower] and [higher] hold, for each
         * option, what moving down or up to it does.
         */
        private fun confirmQualityChange(key: String, lower: Int, higher: Int) {
            val pref = findPreference<ListPreference>(key) ?: return
            pref.setOnPreferenceChangeListener { _, value ->
                val values = pref.entryValues.map { it.toString() }
                val from = values.indexOf(pref.value)
                val to = values.indexOf(value as String)
                if (from < 0 || to < 0 || from == to) return@setOnPreferenceChangeListener true
                val down = to < from
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(if (down) R.string.quality_lower_title else R.string.quality_higher_title)
                    .setMessage(resources.getStringArray(if (down) lower else higher)[to])
                    .setPositiveButton(R.string.quality_proceed) { _, _ -> pref.value = value }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                false
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
            // Stored files change to match these: start on it rather than wait for the next sync.
            if (key == "video_quality" || key == "photo_format" || key == "convert_only_charging") SyncScheduler.syncNow(requireContext())
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
