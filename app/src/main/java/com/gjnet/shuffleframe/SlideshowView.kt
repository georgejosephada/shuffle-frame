package com.gjnet.shuffleframe

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-screen shuffled slideshow of everything downloaded from the enabled albums.
 * Hosted by [SlideshowActivity] and by the [SlideshowDream] screen saver.
 */
@SuppressLint("ViewConstructor")
class SlideshowView(context: Context) : FrameLayout(context) {
    var onExit: (() -> Unit)? = null
    /** True when hosted by the screen saver, which must close for another app to show. */
    var closeToOpenApps = false
    /** Runs the given action once other screens may be shown (e.g. after unlocking). */
    var beforeLeaving: (proceed: () -> Unit) -> Unit = { it() }

    private val scope = MainScope()
    private val prefs = Prefs(context)
    private val playlist = Playlist()
    private val longSide = context.resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) }

    /** A photo plus its blurred, dimmed copy filling the bars around it. */
    private inner class Slot {
        val root = FrameLayout(context).apply { alpha = 0f }
        val bg = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setColorFilter(Color.argb(110, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.MIRROR))
        }
        val img = ImageView(context)
        var motion: Animator? = null

        init {
            root.addView(bg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            root.addView(img, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun set(bmp: Bitmap?) {
            motion?.cancel()
            motion = null
            img.scaleX = 1f; img.scaleY = 1f; img.translationX = 0f; img.translationY = 0f
            img.scaleType = if (prefs.fill) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            img.setImageBitmap(bmp)
            val blur = bmp != null && prefs.blurBackground && !prefs.fill
            bg.setImageBitmap(if (blur) bmp else null)
        }
    }

    private val slots = arrayOf(Slot(), Slot())
    private var front = 0
    private var fadeGeneration = 0

    private val playerView = PlayerView(context).apply {
        useController = false
        setShutterBackgroundColor(Color.BLACK)
        setBackgroundColor(Color.BLACK)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        alpha = 0f
        z = 2f
    }
    private var player: ExoPlayer? = null
    @Volatile private var firstFrame = false
    @Volatile private var videoError = false

    private val caption = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
        val pad = dp(32)
        setPadding(pad, pad, pad, pad)
        z = 3f
    }
    private val warning = TextView(context).apply {
        setTextColor(Color.argb(200, 255, 200, 120))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setShadowLayer(6f, 0f, 1f, Color.BLACK)
        val pad = dp(24)
        setPadding(pad, pad, pad, pad)
        text = context.getString(R.string.slideshow_sync_stale)
        visibility = GONE
        z = 3f
    }
    private val message = TextView(context).apply {
        setTextColor(Color.argb(220, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        gravity = Gravity.CENTER
        val pad = dp(48)
        setPadding(pad, pad, pad, pad)
        visibility = GONE
        z = 3f
    }
    private val overlay: View = LayoutInflater.from(context).inflate(R.layout.slideshow_overlay, this, false)
    private val pauseButton: ImageButton = overlay.findViewById(R.id.btn_pause)
    private val info: TextView = overlay.findViewById(R.id.info)
    private val removePanel: View = overlay.findViewById(R.id.remove_panel)
    private val volumeButton: ImageButton = overlay.findViewById(R.id.btn_volume)
    private val volumeSlider: Slider = overlay.findViewById(R.id.volume_slider)
    private val volumeValue: TextView = overlay.findViewById(R.id.volume_value)
    /** Level the speaker button restores after muting. */
    private var unmuteVolume = 50

    private var current: PlayItem? = null
    /** The item actually on screen (current may still be loading). */
    private var shown: PlayItem? = null
    private var pausedByPanel = false
    private var staleJob: Job? = null
    private var job: Job? = null
    private var watchJob: Job? = null
    private var preload: Pair<String, Deferred<Bitmap?>>? = null
    private var running = false
    private var paused = false
    private var failures = 0
    private var itemCount = 0
    private val hideOverlay = Runnable { setOverlayVisible(false) }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            setOverlayVisible(overlay.visibility != VISIBLE)
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) < abs(vy) || abs(vx) < 600) return false
            if (vx < 0) go(true) else go(false)
            return true
        }
    })

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() { firstFrame = true }
        override fun onPlayerError(error: PlaybackException) { videoError = true }
    }

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
        for (s in slots) addView(s.root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(warning, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        overlay.visibility = GONE
        overlay.z = 4f
        addView(overlay)

        overlay.findViewById<View>(R.id.btn_prev).setOnClickListener { go(false); pokeOverlay() }
        overlay.findViewById<View>(R.id.btn_next).setOnClickListener { go(true); pokeOverlay() }
        pauseButton.setOnClickListener { togglePause(); pokeOverlay() }
        overlay.findViewById<View>(R.id.btn_close).setOnClickListener { onExit?.invoke() }
        overlay.findViewById<View>(R.id.btn_remove).setOnClickListener { showRemovePanel(true) }
        volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) setVolume(value.roundToInt())
        }
        volumeButton.setOnClickListener {
            val v = prefs.videoVolume
            if (v > 0) unmuteVolume = v
            setVolume(if (v > 0) 0 else unmuteVolume)
        }
        syncVolumeControls()
        overlay.findViewById<View>(R.id.remove_cancel).setOnClickListener { showRemovePanel(false) }
        overlay.findViewById<View>(R.id.remove_in_photos).setOnClickListener { removeInGooglePhotos() }
        overlay.findViewById<View>(R.id.remove_hide).setOnClickListener { hideCurrent() }
        overlay.findViewById<View>(R.id.btn_albums).setOnClickListener {
            beforeLeaving {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(MainActivity.EXTRA_NO_AUTOSTART, true)
                )
                onExit?.invoke()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return true
    }

    fun start() {
        if (running) return
        running = true
        if (player == null) {
            player = ExoPlayer.Builder(context).build().also {
                it.addListener(playerListener)
                playerView.player = it
            }
        }
        // Cheap (one request per album when nothing changed), so check for changes on every start.
        SyncScheduler.syncNow(context, userInitiated = false)
        staleJob = scope.launch {
            while (true) {
                val stale = withContext(Dispatchers.IO) { Store.staleAlbums(STALE_MS).isNotEmpty() }
                warning.visibility = if (stale) VISIBLE else GONE
                delay(15 * 60_000L)
            }
        }
        watchJob = scope.launch {
            Store.version.collect {
                reload()
                delay(3000)
            }
        }
    }

    fun stop() {
        running = false
        Store.nowPlaying = null
        job?.cancel(); job = null
        watchJob?.cancel(); watchJob = null
        staleJob?.cancel(); staleJob = null
        preload?.second?.cancel(); preload = null
        player?.stop()
        playerView.alpha = 0f
        slots.forEach { it.motion?.pause() }
    }

    fun release() {
        stop()
        player?.release()
        player = null
        playerView.player = null
        scope.cancel()
    }

    private suspend fun reload() {
        val videos = prefs.includeVideos
        val (all, stale) = withContext(Dispatchers.IO) { Store.playable() to Store.staleAlbums(STALE_MS).isNotEmpty() }
        val items = all.filter { videos || it.type == MediaType.PHOTO }
        warning.visibility = if (stale) VISIBLE else GONE
        itemCount = items.size
        playlist.setItems(items, prefs.balanceAlbums)
        updateInfo()
        if (items.isEmpty()) {
            clearScreen()
            val hasAlbums = withContext(Dispatchers.IO) { Store.albums().isNotEmpty() }
            showMessage(context.getString(if (hasAlbums) R.string.slideshow_waiting else R.string.slideshow_empty))
            return
        }
        if (job?.isActive != true) {
            message.visibility = GONE
            go(true)
        }
    }

    private fun clearScreen() {
        job?.cancel(); job = null
        preload?.second?.cancel(); preload = null
        player?.stop()
        playerView.animate().cancel()
        playerView.alpha = 0f
        fadeGeneration++
        for (s in slots) {
            s.root.animate().cancel()
            s.set(null)
            s.root.alpha = 0f
        }
        caption.visibility = GONE
        current = null
        shown = null
    }

    private fun go(forward: Boolean) {
        if (!running) return
        val item = (if (forward) playlist.next() else playlist.previous()) ?: return
        job?.cancel()
        job = scope.launch { present(item) }
    }

    private suspend fun present(item: PlayItem) {
        current = item
        if (item.type == MediaType.PHOTO) showPhoto(item) else playVideo(item)
    }

    private suspend fun showPhoto(item: PlayItem) {
        val bmp = takePreload(item.key) ?: decode(item.file)
        currentCoroutineContext().ensureActive()
        if (bmp == null) {
            playlist.remove(item.key)
            withContext(Dispatchers.IO) { Store.reportPlaybackFailure(item.key, item.type) }
            failed()
            return
        }
        failures = 0
        message.visibility = GONE
        val incoming = slots[1 - front]
        val outgoing = slots[front]
        front = 1 - front
        incoming.root.animate().cancel()
        incoming.set(bmp)
        shown = item
        incoming.root.alpha = 0f
        incoming.root.z = 1f
        outgoing.root.z = 0f
        val gen = ++fadeGeneration
        incoming.root.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            if (gen == fadeGeneration) {
                outgoing.root.alpha = 0f
                outgoing.set(null)
            }
        }.start()
        startMotion(incoming)
        hideVideo()
        showCaption(item)
        schedulePreload()
        waitFor(prefs.photoSeconds * 1000L)
        go(true)
    }

    private suspend fun playVideo(item: PlayItem) {
        val p = player ?: return
        firstFrame = false
        videoError = false
        Store.nowPlaying = item.key
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(item.file)))
        p.volume = Prefs.gain(prefs.videoVolume)
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.prepare()
        p.playWhenReady = !paused
        var waited = 0L
        while (!firstFrame && !videoError && waited < 20_000) {
            delay(100)
            if (!paused) waited += 100
        }
        if (!firstFrame || videoError) {
            p.stop()
            playlist.remove(item.key)
            withContext(Dispatchers.IO) { Store.reportPlaybackFailure(item.key, item.type) }
            SyncScheduler.syncNow(context, userInitiated = false)
            failed()
            return
        }
        failures = 0
        shown = item
        message.visibility = GONE
        playerView.animate().cancel()
        playerView.animate().alpha(1f).setDuration(FADE_MS).start()
        showCaption(item)
        schedulePreload()
        val cap = prefs.maxVideoSeconds * 1000L
        while (true) {
            delay(250)
            if (videoError || p.playbackState == Player.STATE_ENDED) break
            if (cap > 0 && p.currentPosition >= cap) break
        }
        if (videoError) {
            playlist.remove(item.key)
            withContext(Dispatchers.IO) { Store.reportPlaybackFailure(item.key, item.type) }
            SyncScheduler.syncNow(context, userInitiated = false)
        }
        go(true)
    }

    private fun hideVideo() {
        Store.nowPlaying = null
        playerView.animate().cancel()
        if (playerView.alpha == 0f) {
            player?.stop()
            return
        }
        playerView.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            if (current?.type != MediaType.VIDEO) player?.stop()
        }.start()
    }

    private suspend fun failed() {
        failures++
        if (failures >= 10) {
            showMessage(context.getString(R.string.slideshow_trouble))
            delay(5000)
        }
        go(true)
    }

    private suspend fun waitFor(ms: Long) {
        var left = ms
        while (left > 0) {
            delay(200)
            if (!paused) left -= 200
        }
    }

    private fun startMotion(slot: Slot) {
        if (!prefs.kenBurns) return
        val v = slot.img
        val scale = 1.06f + Math.random().toFloat() * 0.06f
        val dx = (Math.random().toFloat() - 0.5f) * width * 0.04f
        val dy = (Math.random().toFloat() - 0.5f) * height * 0.04f
        slot.motion = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, scale),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, scale),
                ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, dx),
                ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, dy),
            )
            duration = prefs.photoSeconds * 1000L + 2 * FADE_MS
            interpolator = LinearInterpolator()
            start()
            if (paused) pause()
        }
    }

    private fun showCaption(item: PlayItem) {
        if (!prefs.showCaption) {
            caption.visibility = GONE
            return
        }
        val date = if (item.takenAt > 0) {
            DateFormat.getDateInstance(DateFormat.LONG).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(item.takenAt + item.tzOffsetMs))
        } else ""
        caption.text = listOf(date, item.albumTitle).filter { it.isNotBlank() }.joinToString("  ·  ")
        caption.visibility = VISIBLE
    }

    private fun schedulePreload() {
        val next = playlist.peekNext() ?: return
        if (next.type != MediaType.PHOTO || preload?.first == next.key) return
        preload?.second?.cancel()
        preload = next.key to scope.async { decode(next.file) }
    }

    private suspend fun takePreload(key: String): Bitmap? {
        val p = preload ?: return null
        preload = null
        if (p.first != key) {
            p.second.cancel()
            return null
        }
        return try { p.second.await() } catch (e: CancellationException) { currentCoroutineContext().ensureActive(); null }
    }

    private suspend fun decode(file: File): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val m = max(w, h)
                if (m > longSide) {
                    val s = longSide.toFloat() / m
                    decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = VISIBLE
    }

    private fun showRemovePanel(show: Boolean) {
        if (show) {
            if (shown == null) return
            removeCallbacks(hideOverlay)
            pausedByPanel = !paused
            if (!paused) togglePause()
            removePanel.visibility = VISIBLE
        } else {
            closePanel()
            pokeOverlay()
        }
    }

    /** Hides the remove panel and resumes playback if the panel was what paused it. */
    private fun closePanel() {
        if (removePanel.visibility == VISIBLE) {
            removePanel.visibility = GONE
            if (pausedByPanel && paused) togglePause()
        }
        pausedByPanel = false
    }

    /**
     * Opens the item on photos.google.com, where ⋮ → "Remove from album" takes it out of the
     * album (it stays in the library). The sync that runs when the slideshow resumes then
     * deletes the tablet's copy. A browser is used because the Google Photos app opens such
     * links at the top of the album rather than at the item.
     */
    private fun removeInGooglePhotos() {
        val item = shown ?: return
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(item.photosUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = context.packageManager
        val chrome = Intent(view).setPackage(CHROME)
        val browser = Intent(view).apply {
            selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        }
        val target = when {
            chrome.resolveActivity(pm) != null -> chrome
            browser.resolveActivity(pm) != null -> browser
            else -> view
        }
        closePanel()
        setOverlayVisible(false)
        beforeLeaving {
            try {
                context.startActivity(target)
                SyncScheduler.syncSoon(context)
                if (closeToOpenApps) onExit?.invoke()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, R.string.cant_open_photos, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hideCurrent() {
        val item = shown ?: return
        closePanel()
        playlist.remove(item.key)
        scope.launch {
            withContext(Dispatchers.IO) { Store.hide(item.key) }
            go(true)
        }
        pokeOverlay()
    }

    private fun togglePause() {
        paused = !paused
        player?.playWhenReady = !paused
        slots.forEach { if (paused) it.motion?.pause() else it.motion?.resume() }
        pauseButton.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        updateInfo()
    }

    private fun updateInfo() {
        info.text = context.getString(
            if (paused) R.string.slideshow_info_paused else R.string.slideshow_info, itemCount
        )
    }

    private fun setOverlayVisible(show: Boolean) {
        removeCallbacks(hideOverlay)
        if (show) {
            updateInfo()
            syncVolumeControls()
            overlay.alpha = 0f
            overlay.visibility = VISIBLE
            overlay.animate().alpha(1f).setDuration(200).start()
            pokeOverlay()
        } else {
            closePanel()
            overlay.animate().alpha(0f).setDuration(200).withEndAction { overlay.visibility = GONE }.start()
        }
    }

    /** Saves the video volume and applies it to the playing video at once. */
    private fun setVolume(volume: Int) {
        prefs.videoVolume = volume
        player?.volume = Prefs.gain(prefs.videoVolume)
        syncVolumeControls()
        pokeOverlay()
    }

    private fun syncVolumeControls() {
        val v = prefs.videoVolume
        volumeSlider.value = ((v + 2) / 5 * 5).toFloat().coerceIn(0f, 100f)
        volumeValue.text = "$v%"
        volumeButton.setImageResource(if (v == 0) R.drawable.ic_volume_off else R.drawable.ic_volume)
        volumeButton.contentDescription = context.getString(if (v == 0) R.string.cd_unmute else R.string.cd_mute)
    }

    private fun pokeOverlay() {
        removeCallbacks(hideOverlay)
        if (removePanel.visibility != VISIBLE) postDelayed(hideOverlay, 6000)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val FADE_MS = 900L
        const val STALE_MS = 24 * 60 * 60 * 1000L
        const val CHROME = "com.android.chrome"
    }
}
