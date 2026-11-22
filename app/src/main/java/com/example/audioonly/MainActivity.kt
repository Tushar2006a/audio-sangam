package com.example.audioonly

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One simple screen: paste a link, play the audio, control it.
 * Built from plain Android views (no extra UI libraries) to keep the APK small.
 */
class MainActivity : Activity() {

    private val bg = Color.parseColor("#121212")
    private val accent = Color.parseColor("#4FC3F7")

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var dragging = false
    private var pendingLoad = false

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var playPause: ImageButton
    private lateinit var shuffleBtn: Button
    private lateinit var repeatBtn: Button

    private val queueAdapter = object : BaseAdapter() {
        override fun getCount(): Int = controller?.mediaItemCount ?: 0
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val c = controller
            val title = c?.getMediaItemAt(position)?.mediaMetadata?.title ?: "..."
            tv.text = "${position + 1}. $title"
            tv.setTextColor(if (c != null && c.currentMediaItemIndex == position) accent else Color.LTGRAY)
            return tv
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }

        override fun onPlayerError(error: PlaybackException) {
            status.text = "Couldn't play this one: ${error.cause?.message ?: error.errorCodeName}"
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, 500)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(playerListener)
                refresh()
                if (pendingLoad) {
                    pendingLoad = false
                    load(true)
                }
            } catch (e: Exception) {
                status.text = "Couldn't start the player"
            }
        }, MoreExecutors.directExecutor())
        ui.post(ticker)
    }

    override fun onStop() {
        ui.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- actions

    /** Links shared from other apps ("Share" -> Audio Only). */
    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        input.setText(url)
        if (controller != null) load(true) else pendingLoad = true
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrBlank()) {
            status.text = "Clipboard is empty"
        } else {
            input.setText(text.trim())
        }
    }

    /** replace = true: start playing this now. replace = false: add to the end of the queue. */
    private fun load(replace: Boolean) {
        val link = input.text.toString().trim()
        if (link.isEmpty()) {
            status.text = "Paste a link first"
            return
        }
        status.text = "Loading..."
        io.execute {
            try {
                val tracks = LinkResolver.loadTracks(link)
                ui.post { enqueue(tracks, replace) }
            } catch (e: Exception) {
                ui.post { status.text = "Couldn't open that link: ${e.message}" }
            }
        }
    }

    private fun enqueue(tracks: List<LinkResolver.Track>, replace: Boolean) {
        val c = controller
        if (c == null) {
            status.text = "Player isn't ready yet, try again in a second"
            return
        }
        val items = tracks.map { t ->
            MediaItem.Builder()
                .setUri(t.pageUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setArtworkUri(t.artworkUrl?.let { Uri.parse(it) })
                        .build()
                )
                .build()
        }
        if (replace) {
            c.setMediaItems(items)
            c.prepare()
            c.play()
        } else {
            val wasEmpty = c.mediaItemCount == 0
            c.addMediaItems(items)
            if (wasEmpty) {
                c.prepare()
                c.play()
            }
        }
        status.text = if (tracks.size == 1) "Added 1 track" else "Added ${tracks.size} tracks"
    }

    private fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    // ---------------------------------------------------------------- screen updates

    private fun refresh() {
        val c = controller ?: return
        val md = c.mediaMetadata
        nowTitle.text = md.title ?: "Nothing playing"
        nowArtist.text = md.artist ?: ""

        val showPause = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        playPause.setImageResource(
            if (showPause) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )

        shuffleBtn.text = if (c.shuffleModeEnabled) "Shuffle: on" else "Shuffle: off"
        repeatBtn.text = when (c.repeatMode) {
            Player.REPEAT_MODE_ALL -> "Repeat: all"
            Player.REPEAT_MODE_ONE -> "Repeat: one"
            else -> "Repeat: off"
        }

        if (c.playbackState == Player.STATE_BUFFERING && c.playWhenReady) {
            status.text = "Loading..."
        } else if (status.text.toString() == "Loading...") {
            status.text = ""
        }

        queueAdapter.notifyDataSetChanged()
        tick()
    }

    private fun tick() {
        val c = controller ?: return
        val rawDuration = c.duration
        val duration = if (rawDuration == C.TIME_UNSET || rawDuration < 0) 0L else rawDuration
        seek.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        total.text = fmt(duration)
        if (!dragging) {
            val pos = c.currentPosition.coerceIn(0L, duration)
            seek.progress = pos.toInt()
            elapsed.text = fmt(pos)
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000).toInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.US, "%d:%02d", m, sec)
        }
    }

    // ---------------------------------------------------------------- building the screen

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun textButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    private fun iconButton(res: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "Audio Only"
            textSize = 22f
            setTextColor(Color.WHITE)
        })

        input = EditText(this).apply {
            hint = "Paste a video, song or playlist link"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ ->
                load(true)
                true
            }
        }
        root.addView(input, LinearLayout.LayoutParams(match, wrap))

        val linkButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(textButton("Paste") { pasteFromClipboard() }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(textButton("Play") { load(true) }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(textButton("Add to queue") { load(false) }, LinearLayout.LayoutParams(0, wrap, 1f))
        }
        root.addView(linkButtons, LinearLayout.LayoutParams(match, wrap))

        status = TextView(this).apply {
            text = "Paste a link and tap Play"
            textSize = 13f
            setTextColor(accent)
        }
        root.addView(status)

        nowTitle = TextView(this).apply {
            text = "Nothing playing"
            textSize = 18f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(nowTitle)

        nowArtist = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(nowArtist)

        elapsed = TextView(this).apply {
            text = "0:00"
            setTextColor(Color.LTGRAY)
        }
        total = TextView(this).apply {
            text = "0:00"
            setTextColor(Color.LTGRAY)
        }
        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsed.text = fmt(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                controller?.seekTo(sb.progress.toLong())
            }
        })
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(elapsed)
            addView(seek, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(total)
        }
        root.addView(seekRow, LinearLayout.LayoutParams(match, wrap))

        playPause = iconButton(android.R.drawable.ic_media_play) { togglePlayPause() }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(
                iconButton(android.R.drawable.ic_media_previous) { controller?.seekToPrevious() },
                LinearLayout.LayoutParams(dp(72), dp(72))
            )
            addView(playPause, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(
                iconButton(android.R.drawable.ic_media_next) { controller?.seekToNext() },
                LinearLayout.LayoutParams(dp(72), dp(72))
            )
        }
        root.addView(controls, LinearLayout.LayoutParams(match, wrap))

        shuffleBtn = textButton("Shuffle: off") {
            controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
        repeatBtn = textButton("Repeat: off") {
            controller?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(shuffleBtn, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(repeatBtn, LinearLayout.LayoutParams(0, wrap, 1f))
        }
        root.addView(modes, LinearLayout.LayoutParams(match, wrap))

        val queue = ListView(this).apply {
            adapter = queueAdapter
            setOnItemClickListener { _, _, position, _ ->
                controller?.let {
                    it.seekToDefaultPosition(position)
                    it.play()
                }
            }
        }
        root.addView(queue, LinearLayout.LayoutParams(match, 0, 1f))

        setContentView(root)
    }
}
