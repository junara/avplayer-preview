package io.github.junara.avpreview

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.*
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.beans.PropertyChangeListener
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class VideoEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) =
        System.getProperty("os.name").startsWith("Mac") && file.isInLocalFileSystem &&
            !file.isDirectory && file.extension?.lowercase() in setOf("mp4", "m4v", "mov")
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = VideoEditor(file)
    override fun getEditorTypeId() = "avplayer-preview"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class VideoCanvas : JPanel() {
    var image: BufferedImage? = null
    init { background = Color.BLACK; preferredSize = Dimension(800, 450) }
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val frame = image ?: return
        val scale = minOf(width.toDouble()/frame.width, height.toDouble()/frame.height)
        val w = (frame.width*scale).toInt(); val h = (frame.height*scale).toInt()
        g.drawImage(frame, (width-w)/2, (height-h)/2, w, h, null)
    }
}

/** One row of the info table; [value] == null marks a section header. */
private data class InfoRow(val key: String, val value: String?)
/** [complete] is false when native metadata failed or timed out; the next display of the panel retries. */
private class InfoResult(val rows: List<InfoRow>, val complete: Boolean)

private class InfoTableModel : AbstractTableModel() {
    var rows: List<InfoRow> = emptyList()
        set(value) { field = value; fireTableDataChanged() }
    override fun getRowCount() = rows.size
    override fun getColumnCount() = 2
    override fun getColumnName(column: Int) = if(column == 0) "Property" else "Value"
    override fun getValueAt(row: Int, column: Int): String = rows[row].let { if(column == 0) it.key else it.value ?: "" }
}

class VideoEditor(private val video: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val root = JPanel(BorderLayout())
    private val canvas = VideoCanvas()
    private val status = JLabel("Loading AVPlayer…")
    private val play = JButton("Play")
    private val seek = JSlider(0, 1000, 0)
    /** Last saved volume (0–100), applied to the slider and to the player once it opens. */
    private val savedVolume = PropertiesComponent.getInstance().getInt(VOLUME_KEY, 100).coerceIn(0, 100)
    private val volume = JSlider(0, 100, savedVolume)
    /** Newest volume not yet sent to the worker; non-null means a send is already queued. */
    private val pendingVolume = AtomicReference<Double?>()
    private val speed = JComboBox(arrayOf("0.5×", "1×", "1.5×", "2×"))
    private val infoModel = InfoTableModel()
    private val infoScroll = JBScrollPane(JBTable(infoModel).apply {
        tableHeader.reorderingAllowed = false
        setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component =
                super.getTableCellRendererComponent(t, v, sel, focus, row, col).also {
                    font = if(infoModel.rows[row].value == null) font.deriveFont(Font.BOLD) else font
                    toolTipText = v?.toString()
                }
        })
    }).apply { preferredSize = Dimension(300, 0) }
    private val infoButton = JToggleButton("Info", PropertiesComponent.getInstance().getBoolean(INFO_KEY, false))
    /** Result computed off the EDT, waiting to be shown by the EDT. */
    private val latestInfo = AtomicReference<InfoResult?>()
    /** EDT-owned. Set while loading or after a complete result; cleared after an incomplete one to allow a retry. */
    private var infoRequested = false
    /** Native session id once open finished (0 when it failed). Lets info loading skip the playback worker. */
    private val opened = CompletableFuture<Long>()
    private val closed = AtomicBoolean(false)
    private val latestFrame = AtomicReference<BufferedImage?>()
    private val latestState = AtomicReference<DoubleArray?>()
    private val failure = AtomicReference<String?>()
    /** Images the EDT no longer displays; the worker reuses them instead of allocating per frame. */
    private val recycled = AtomicReference<BufferedImage?>()
    /** Polling runs only while the tab is selected (or before the first selection notification). */
    private val active = AtomicBoolean(true)
    private val uiQueued = AtomicBoolean(false)
    private val worker = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "AVPlayer-preview").apply { isDaemon = true }
    }.apply { executeExistingDelayedTasksAfterShutdownPolicy = false; removeOnCancelPolicy = true }
    private var handle = 0L // worker-owned
    private var pollTask: ScheduledFuture<*>? = null // worker-owned
    private var spare: BufferedImage? = null // worker-owned
    /** Worker-owned. After a seek, poll at 30 Hz until its frame arrives (capped), since seeks are async. */
    private var awaitingFrame = false
    private var fastUntil = 0L // System.nanoTime() deadline for awaitingFrame
    private var nextFrameAt = 0L // worker-owned System.nanoTime() target of the next playing poll
    private var duration = 0.0 // EDT-owned
    private var playing = false
    private var updatingSeek = false
    private var wasDragging = false

    init {
        val controls = JPanel(FlowLayout(FlowLayout.LEFT))
        speed.selectedIndex = 1
        seek.preferredSize = Dimension(220, 24)
        volume.preferredSize = Dimension(85, 24)
        controls.add(play); controls.add(seek); controls.add(speed)
        controls.add(JLabel("Volume")); controls.add(volume); controls.add(infoButton)
        val bottom = JPanel(BorderLayout())
        bottom.add(controls, BorderLayout.CENTER); bottom.add(status, BorderLayout.SOUTH)
        root.add(canvas, BorderLayout.CENTER); root.add(bottom, BorderLayout.SOUTH); root.add(infoScroll, BorderLayout.EAST)
        infoScroll.isVisible = infoButton.isSelected
        infoButton.addActionListener {
            infoScroll.isVisible = infoButton.isSelected; root.revalidate()
            PropertiesComponent.getInstance().setValue(INFO_KEY, infoButton.isSelected, false)
            if(infoButton.isSelected) requestInfo()
        }
        play.isEnabled = false; seek.isEnabled = false
        play.addActionListener {
            val shouldPlay = !playing
            val rate = selectedRate()
            submit {
                if (shouldPlay) {
                    val s = NativePlayer.state(handle)
                    if(s[2] > 0 && s[1] >= s[2] - 0.05) NativePlayer.seek(handle, 0.0)
                    NativePlayer.play(handle, rate)
                } else NativePlayer.pause(handle)
            }
        }
        speed.addActionListener { val rate = selectedRate(); if(playing) submit { NativePlayer.play(handle,rate) } }
        volume.addChangeListener {
            // Coalesce drag ticks: at most one queued task, which sends the newest value.
            if(pendingVolume.getAndSet(volume.value/100.0) == null)
                submit { pendingVolume.getAndSet(null)?.let { NativePlayer.volume(handle,it) } }
            if(!volume.valueIsAdjusting) PropertiesComponent.getInstance().setValue(VOLUME_KEY, volume.value, 100)
        }
        seek.addChangeListener {
            if(!updatingSeek && duration>0) {
                if(seek.valueIsAdjusting) wasDragging=true
                else {
                    val target=duration*seek.value/1000.0
                    wasDragging=false
                    submit(expectFrame = true) { NativePlayer.seek(handle,target) }
                }
            }
        }
        worker.execute {
            try {
                NativePlayer.load()
                handle=NativePlayer.open(video.path)
                opened.complete(handle)
                check(handle != 0L) { "Cannot open file: ${video.name}" }
                // Best effort: a failed volume restore must not fail an otherwise playable file.
                try { NativePlayer.volume(handle, savedVolume/100.0) } catch(_: Exception) {}
                schedulePoll(0)
            } catch(t: Throwable) { opened.complete(0L); fail(t.message ?: t.javaClass.simpleName) }
        }
        if(infoButton.isSelected) requestInfo()
    }
    private fun selectedRate() = doubleArrayOf(0.5,1.0,1.5,2.0)[speed.selectedIndex]
    private fun submit(expectFrame: Boolean = false, action: () -> Unit) {
        if(closed.get()) return
        worker.execute {
            if(handle != 0L && !closed.get()) {
                try { action() } catch(t: Throwable) { fail(t.message ?: "Playback error") }
                if(expectFrame) {
                    awaitingFrame=true
                    fastUntil=System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_FRAME_WAIT_MS)
                }
                schedulePoll(0) // reflect the command immediately
            }
        }
    }
    private fun fail(message: String) { failure.set(message); requestUi() }
    /** Coalesces worker results into at most one pending EDT update. */
    private fun requestUi() {
        if(uiQueued.compareAndSet(false,true)) SwingUtilities.invokeLater { uiQueued.set(false); updateUi() }
    }
    /** Worker only. Replaces any pending poll so commands never stack extra polling loops. */
    private fun schedulePoll(delay: Long, unit: TimeUnit = TimeUnit.MILLISECONDS) {
        pollTask?.cancel(false)
        pollTask = try { worker.schedule(::poll, delay, unit) } catch(e: RejectedExecutionException) { null } // disposed
    }
    /** Worker only. ~30 Hz while playing, slower while loading or paused, stopped while hidden. */
    private fun poll() {
        if(closed.get() || !active.get() || handle == 0L || failure.get() != null) return
        try {
            val state=NativePlayer.state(handle)
            latestState.set(state)
            if(state[0]==2.0) { fail(NativePlayer.error(handle)); return }
            if(pollFrame()) awaitingFrame=false
            requestUi()
            val fast=state[3] > 0 || (awaitingFrame && System.nanoTime() - fastUntil < 0)
            if(!fast) { schedulePoll(if(state[0] == 0.0) 100L else 250L); return }
            // Fixed-rate against a deadline: fetch/scale time and late timer wake-ups (macOS coalesces
            // timers by a few ms) would otherwise stretch every period and drop the rate to ~21-27 fps.
            val now=System.nanoTime()
            nextFrameAt+=FRAME_PERIOD_NS
            // Just started, fell far behind (no burst), or ran ahead after an out-of-band command poll.
            if(now - nextFrameAt > FRAME_PERIOD_NS || nextFrameAt - now > FRAME_PERIOD_NS) nextFrameAt=now + FRAME_PERIOD_NS
            schedulePoll((nextFrameAt - now).coerceIn(0L, FRAME_PERIOD_NS), TimeUnit.NANOSECONDS)
        } catch(t: Throwable) { fail(t.message ?: t.javaClass.simpleName) }
    }
    /**
     * EDT only. Loads metadata on a pooled thread once open has finished, so slow metadata I/O never waits
     * behind or delays the playback worker. The task captures no editor reference: a closed tab is not kept alive.
     */
    private fun requestInfo() {
        if(infoRequested || closed.get()) return
        infoRequested=true
        val file=video; val sink=latestInfo; val isClosed=closed; val editor=WeakReference(this)
        opened.thenAcceptAsync({ id ->
            if(!isClosed.get()) {
                // Anything unexpected (e.g. the file vanished) still ends in a visible, retryable result.
                val result=try { loadInfo(file, id, isClosed) } catch(t: Throwable) {
                    LOG.warn("Reading video info failed: ${file.path}", t)
                    InfoResult(listOf(InfoRow("General", null), InfoRow("File", file.name),
                        InfoRow("Metadata", "Error: ${t.message ?: t.javaClass.simpleName}")), false)
                }
                if(!isClosed.get()) { sink.set(result); editor.get()?.requestUi() }
            }
        }, INFO_EXECUTOR)
    }
    /** Returns true when a new frame was published. */
    private fun pollFrame(): Boolean {
        var image=spare ?: recycled.getAndSet(null)
        spare=null
        var result=NativePlayer.frame(handle, image?.pixels())
        if(result < 0) { // first frame or size change: allocate once, native kept the frame for us
            image=BufferedImage(-result ushr 16, -result and 0xFFFF, BufferedImage.TYPE_INT_RGB)
            result=NativePlayer.frame(handle, image.pixels())
        }
        if(result <= 0) { spare=image; return false }
        latestFrame.getAndSet(image)?.let { spare=it } // EDT skipped it: reuse
        return true
    }
    private fun BufferedImage.pixels() = (raster.dataBuffer as DataBufferInt).data
    private fun updateUi() {
        if(closed.get()) return
        latestInfo.getAndSet(null)?.let { // before the failure return: info helps diagnose
            infoModel.rows=it.rows
            if(!it.complete) infoRequested=false // hiding and showing the panel again retries
        }
        failure.get()?.let {
            status.text="Playback failed: $it"; status.toolTipText=it
            play.isEnabled=false; seek.isEnabled=false
            return
        }
        latestFrame.getAndSet(null)?.let { frame ->
            // Painting happens on the EDT too, so the replaced image is no longer read once handed back.
            canvas.image?.let { recycled.set(it) }
            canvas.image=frame; canvas.repaint()
        }
        latestState.get()?.let { s ->
            duration=s[2]; playing=s[3]>0
            play.text=if(playing) "Pause" else "Play"
            play.isEnabled=s[0]==1.0; seek.isEnabled=s[0]==1.0 && duration>0
            status.text=if(s[0]==0.0) "Loading…" else "${time(s[1])} / ${time(duration)} · AVPlayer · preview ≤1280px / 30fps"
            if(!seek.valueIsAdjusting && !wasDragging) {
                updatingSeek=true
                seek.value=if(duration>0) (s[1]/duration*1000).toInt().coerceIn(0,1000) else 0
                updatingSeek=false
            }
        }
    }
    private fun time(value: Double): String {
        val s=value.toLong().coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s/3600,s/60%60,s%60)
    }
    override fun selectNotify() { active.set(true); submit {} }
    override fun deselectNotify() { active.set(false); submit { NativePlayer.pause(handle) } }
    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = play
    override fun getName() = "AVPlayer Preview"
    override fun getFile() = video
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = video.isValid && !closed.get()
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {
        if(!closed.compareAndSet(false,true)) return
        // Never wait for AppKit from EDT: disposal is serialized behind pending calls.
        worker.execute {
            pollTask?.cancel(false)
            if(handle != 0L) { NativePlayer.close(handle); handle=0 }
            latestFrame.set(null); recycled.set(null); spare=null
        }
        worker.shutdown()
        canvas.image=null
    }
}

private val LOG = Logger.getInstance(VideoEditor::class.java)
/** Metadata reads can block for seconds; a small dedicated pool keeps them off the shared app pool. */
private val INFO_EXECUTOR = AppExecutorUtil.createBoundedApplicationPoolExecutor("AVPlayer metadata", 2)

/**
 * Pooled thread. File rows plus native stream metadata. [id] may be 0 (open failed): file rows still show.
 * Native failures are logged and shown as a "Metadata" row instead of being dropped silently.
 */
private fun loadInfo(file: VirtualFile, id: Long, closed: AtomicBoolean): InfoResult {
    val rows=mutableListOf(InfoRow("General", null), InfoRow("File", file.name))
    val bytes=file.length
    rows+=InfoRow("Size", "${StringUtil.formatFileSize(bytes)} (${"%,d".format(bytes)} bytes)")
    rows+=InfoRow("Container", container(file))
    if(id==0L) { rows+=InfoRow("Metadata", "Unavailable: the file could not be opened"); return InfoResult(rows, false) }
    // Throwable: also covers UnsatisfiedLinkError from a mismatched native library.
    val native=try { NativePlayer.info(id, bytes) } catch(t: Throwable) {
        LOG.warn("Reading video metadata failed: ${file.path}", t)
        rows+=InfoRow("Metadata", "Error: ${t.message ?: t.javaClass.simpleName}")
        return InfoResult(rows, false)
    }
    if(native==null) {
        if(!closed.get()) rows+=InfoRow("Metadata", "Unavailable: the player session is gone")
        return InfoResult(rows, false)
    }
    var complete=true
    native.lineSequence().filter { it.isNotBlank() }.forEach { line ->
        val tab=line.indexOf('\t')
        val row=if(tab<0) InfoRow(line, null) else InfoRow(line.substring(0,tab), line.substring(tab+1))
        if(row.key=="Metadata") { complete=false; LOG.info("Video metadata incomplete for ${file.path}: ${row.value}") }
        rows+=row
    }
    return InfoResult(rows, complete)
}

/** Detected from the file header (ISO BMFF "ftyp" brand), never guessed from the extension. */
private fun container(file: VirtualFile): String {
    val header=ByteArray(12)
    val read=try { file.inputStream.use { it.readNBytes(header, 0, header.size) } } catch(_: Exception) { return "Unknown" }
    fun fourCC(at: Int): String? =
        if(read>=at+4 && (at until at+4).all { header[it] in 0x20..0x7E }) String(header, at, 4, Charsets.US_ASCII) else null
    val type=fourCC(4) ?: return "Unknown"
    // Classic QuickTime files predate ftyp and start directly with one of these atoms.
    if(type!="ftyp") return if(type in CLASSIC_QUICKTIME_ATOMS) "QuickTime (classic, no ftyp)" else "Unknown (first box '$type')"
    val brand=fourCC(8)?.trimEnd() ?: return "Unknown (unreadable ftyp brand)"
    val name=when(brand) {
        "qt" -> "QuickTime"
        "M4V", "M4VH", "M4VP" -> "MPEG-4 (iTunes video)"
        "M4A" -> "MPEG-4 (audio)"
        "3gp4", "3gp5", "3gp6", "3g2a" -> "3GPP"
        else -> "MPEG-4"
    }
    return "$name (brand $brand)"
}

private val CLASSIC_QUICKTIME_ATOMS = setOf("moov", "mdat", "wide", "free", "skip", "pnot", "PICT")
private const val MAX_FRAME_WAIT_MS = 1000L
private val FRAME_PERIOD_NS = TimeUnit.SECONDS.toNanos(1) / 30 // poll rate while playing
private const val VOLUME_KEY = "io.github.junara.avpreview.volume"
private const val INFO_KEY = "io.github.junara.avpreview.infoVisible"
