package com.gjnet.shuffleframe

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Album manager: which albums are in the slideshow, their sync state, and the start button. */
class MainActivity : AppCompatActivity() {
    private lateinit var adapter: AlbumAdapter
    private lateinit var root: View
    private lateinit var summary: TextView
    private lateinit var syncStatus: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var emptyHint: View
    private lateinit var setupCard: View
    private lateinit var setupSteps: android.widget.LinearLayout

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        root.padForSystemBars()
        summary = findViewById(R.id.summary)
        syncStatus = findViewById(R.id.sync_status)
        progress = findViewById(R.id.progress)
        emptyHint = findViewById(R.id.empty_hint)
        setupCard = findViewById(R.id.setup_card)
        setupSteps = findViewById(R.id.setup_steps)

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        findViewById<Button>(R.id.start_button).setOnClickListener { startSlideshow() }
        findViewById<Button>(R.id.sync_button).setOnClickListener {
            SyncScheduler.syncNow(this)
            Snackbar.make(root, R.string.sync_queued, Snackbar.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.add_button).setOnClickListener { showAddDialog() }

        adapter = AlbumAdapter(
            onToggle = { album, enabled ->
                lifecycleScope.launch(Dispatchers.IO) { Store.updateAlbum(album.key) { it.copy(enabled = enabled) } }
                if (enabled) SyncScheduler.syncNow(this, album.key)
            },
            onClick = { showAlbumOptions(it) },
        )
        val list = findViewById<RecyclerView>(R.id.album_list)
        val wide = resources.configuration.screenWidthDp >= 840
        list.layoutManager = GridLayoutManager(this, if (wide) 2 else 1)
        list.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Store.version.collect { refresh() } }
                launch { Store.status.collect { renderStatus(it) } }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (savedInstanceState == null) handleIntent(intent, launched = true)
    }

    override fun onResume() {
        super.onResume()
        // Being in the app unlocked means the user is here; Android may also resume this
        // screen behind the lock screen after a frame closes, which must not count.
        val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        if (!locked && getSystemService(android.os.PowerManager::class.java).isInteractive) {
            FrameService.setSnoozed(this, false)
        }
        renderSetup()
        FrameService.sync(this)
    }

    /** Shows a checklist of permissions still missing, each with a button that opens the right page. */
    private fun renderSetup() {
        val steps = Setup.missing(this)
        setupCard.visibility = if (steps.isEmpty()) View.GONE else View.VISIBLE
        setupSteps.removeAllViews()
        for (step in steps) {
            val b = com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            b.text = getString(step.title)
            b.setOnClickListener {
                runCatching { startActivity(step.intent) }.onFailure {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            setupSteps.addView(b)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, launched = false)
    }

    /** Albums shared from the Google Photos app (Share → Shuffle Frame) arrive here. */
    private fun handleIntent(intent: Intent?, launched: Boolean) {
        if (intent?.action == Intent.ACTION_SEND) {
            val link = GooglePhotos.findLink(intent.getStringExtra(Intent.EXTRA_TEXT))
            setIntent(Intent(this, MainActivity::class.java))
            if (link == null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.add_failed)
                    .setMessage(R.string.no_link_in_share)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else addAlbum(link)
        } else if (launched && Prefs(this).autoStart && intent?.getBooleanExtra(EXTRA_NO_AUTOSTART, false) != true) {
            lifecycleScope.launch {
                if (withContext(Dispatchers.IO) { Store.playable().isNotEmpty() }) startSlideshow()
            }
        }
    }

    private fun startSlideshow() = startActivity(Intent(this, SlideshowActivity::class.java))

    private suspend fun refresh() {
        val (list, bytes) = withContext(Dispatchers.IO) { Store.summaries() to Store.storageBytes() }
        adapter.submitList(list)
        emptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val on = list.filter { it.album.enabled }
        val photos = on.sumOf { it.photos }
        val videos = on.sumOf { it.videos }
        summary.text = if (list.isEmpty()) getString(R.string.summary_none) else getString(
            R.string.summary,
            resources.getQuantityString(R.plurals.photos, photos, photos),
            resources.getQuantityString(R.plurals.videos, videos, videos),
            resources.getQuantityString(R.plurals.albums, on.size, on.size),
            formatBytes(bytes),
        )
    }

    private fun renderStatus(s: SyncStatus) {
        progress.visibility = if (s.running) View.VISIBLE else View.GONE
        syncStatus.text = s.message.ifEmpty { getString(R.string.sync_idle) }
    }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add, null)
        val input = view.findViewById<TextInputEditText>(R.id.link_input)
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        GooglePhotos.findLink(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString())
            ?.let { input.setText(it) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_album)
            .setView(view)
            .setPositiveButton(R.string.add) { _, _ ->
                val link = GooglePhotos.findLink(input.text?.toString())
                if (link == null) Toast.makeText(this, R.string.no_link_in_share, Toast.LENGTH_LONG).show()
                else addAlbum(link)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addAlbum(link: String) {
        lifecycleScope.launch {
            val bar = Snackbar.make(root, R.string.adding, Snackbar.LENGTH_INDEFINITE).also { it.show() }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val r = GooglePhotos.resolve(link)
                    val added = Store.addAlbum(Album(r.albumKey, r.authKey, link, r.title))
                    // Scheduled here so it survives the activity being recreated mid-add.
                    if (added) SyncScheduler.syncNow(applicationContext, r.albumKey)
                    r to added
                }
            }
            bar.dismiss()
            result.onSuccess { (r, added) ->
                Snackbar.make(
                    root,
                    getString(if (added) R.string.added else R.string.already_added, r.title),
                    Snackbar.LENGTH_LONG,
                ).show()
            }.onFailure { e ->
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.add_failed)
                    .setMessage(e.message ?: e.javaClass.simpleName)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showAlbumOptions(s: AlbumSummary) {
        val a = s.album
        val actions = buildList<Pair<String, () -> Unit>> {
            add(getString(R.string.sync_this_album) to { SyncScheduler.syncNow(this@MainActivity, a.key) })
            add(getString(R.string.open_in_photos) to {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.shareUrl))) }
            })
            if (s.hidden > 0) add(resources.getQuantityString(R.plurals.unhide, s.hidden, s.hidden) to {
                lifecycleScope.launch(Dispatchers.IO) { Store.unhideAll(a.key) }
            })
            add(getString(R.string.remove_album) to { confirmRemove(a) })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(a.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    companion object {
        /** Set when returning from the slideshow, so auto-start doesn't bounce straight back. */
        const val EXTRA_NO_AUTOSTART = "no_autostart"
    }

    private fun confirmRemove(a: Album) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.remove_title, a.title))
            .setMessage(R.string.remove_message)
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { Store.removeAlbum(a.key) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
