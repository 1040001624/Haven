package sh.haven.core.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** What kind of media the agent wants the user to perceive. */
enum class PresentedMediaKind {
    IMAGE,
    AUDIO,
    /**
     * A live, interactive single-app window: a GUI app running under a cage
     * kiosk in the guest, reached over VNC at [PresentedMedia.host]:[port].
     * The overlay embeds the VNC viewer rather than decoding a file.
     */
    APP_WINDOW,
}

/**
 * One thing an agent has pushed for the user to look at / listen to /
 * interact with. For IMAGE/AUDIO the bytes live in a cache file
 * ([filePath]) — cheap to keep in the StateFlow and what the image decoder
 * / audio player want. For APP_WINDOW there is no file: it carries the VNC
 * [host]/[port]/[sessionId] of a live cage-kiosk session instead.
 */
data class PresentedMedia(
    val id: Long,
    val kind: PresentedMediaKind,
    /** IMAGE/AUDIO: absolute cache-file path the UI reads/decodes/plays. */
    val filePath: String? = null,
    val mimeType: String? = null,
    /** Optional one-line caption shown above the media. */
    val caption: String? = null,
    /** Audio only: start playback as soon as the sheet appears. */
    val autoPlay: Boolean = false,
    /** APP_WINDOW: VNC endpoint + the DesktopManager session to stop on dismiss. */
    val host: String? = null,
    val port: Int? = null,
    val sessionId: String? = null,
    val presentedAt: Long = System.currentTimeMillis(),
)

/**
 * The agent → user "here, look at / listen to this" channel.
 *
 * Unlike [AgentUiCommandBus] (fire-and-forget navigation *commands* with
 * `replay = 0`), a presented image or sound is **state**: it must stay on
 * screen until the user dismisses it, and survive a recomposition or a
 * brief backgrounding. So this mirrors [AgentConsentManager]'s shape — a
 * `@Singleton` holding a [StateFlow] queue the top-of-tree host renders.
 *
 * It is deliberately *not* a consent gate. Showing an image is not a
 * destructive act, so [present] never suspends and never asks: the agent
 * calls it and returns immediately; the overlay is itself the user-facing
 * artifact, and is freely dismissible. The user keeps the wheel by
 * dismissing, not by pre-approving.
 *
 * ### Backing files
 *
 * Each entry owns a cache file written by the caller. [dismiss] deletes
 * it. To bound disk use against a chatty agent the queue is capped at
 * [MAX_QUEUE]; pushing past the cap drops (and deletes the file of) the
 * oldest entry.
 */
@Singleton
class AgentPresentationManager @Inject constructor() {

    private val nextId = AtomicLong(1)

    private val _pending = MutableStateFlow<List<PresentedMedia>>(emptyList())
    /** All currently-showing media, oldest first. Drives the presentation host. */
    val pending: StateFlow<List<PresentedMedia>> = _pending.asStateFlow()

    /**
     * Enqueue [filePath] for the user to see/hear. Non-blocking; returns
     * the assigned id so a caller could correlate a later [dismiss] if it
     * wanted to. The file at [filePath] must already exist and is owned by
     * this manager from here on — it is deleted on dismissal / eviction.
     */
    fun present(
        kind: PresentedMediaKind,
        filePath: String,
        mimeType: String,
        caption: String?,
        autoPlay: Boolean = false,
    ): Long = enqueue(
        PresentedMedia(
            id = nextId.getAndIncrement(),
            kind = kind,
            filePath = filePath,
            mimeType = mimeType,
            caption = caption,
            autoPlay = autoPlay,
        ),
    )

    /**
     * Enqueue a live [PresentedMediaKind.APP_WINDOW] backed by a cage-kiosk
     * VNC session at [host]:[port]. [sessionId] is the DesktopManager session
     * the UI stops when the window is dismissed. No cache file is involved.
     */
    fun presentAppWindow(
        host: String,
        port: Int,
        sessionId: String,
        caption: String?,
    ): Long = enqueue(
        PresentedMedia(
            id = nextId.getAndIncrement(),
            kind = PresentedMediaKind.APP_WINDOW,
            caption = caption,
            host = host,
            port = port,
            sessionId = sessionId,
        ),
    )

    private fun enqueue(item: PresentedMedia): Long {
        val next = _pending.value + item
        if (next.size > MAX_QUEUE) {
            // Evict and delete the backing file of the oldest entries so a
            // misbehaving agent can't fill the cache. (APP_WINDOW has no file.)
            val evicted = next.subList(0, next.size - MAX_QUEUE)
            evicted.forEach { it.filePath?.let { p -> runCatching { File(p).delete() } } }
            _pending.value = next.subList(next.size - MAX_QUEUE, next.size).toList()
        } else {
            _pending.value = next
        }
        return item.id
    }

    /**
     * Called by the UI when the user dismisses an item (taps Dismiss or
     * swipes the sheet away). Removes it from the queue and deletes its
     * backing cache file (IMAGE/AUDIO only). For APP_WINDOW the UI layer is
     * responsible for stopping the DesktopManager session — this manager
     * (core:data) doesn't depend on core:local.
     */
    fun dismiss(id: Long) {
        val current = _pending.value
        val item = current.firstOrNull { it.id == id }
        _pending.value = current.filterNot { it.id == id }
        item?.filePath?.let { runCatching { File(it).delete() } }
    }

    private companion object {
        const val MAX_QUEUE = 8
    }
}
