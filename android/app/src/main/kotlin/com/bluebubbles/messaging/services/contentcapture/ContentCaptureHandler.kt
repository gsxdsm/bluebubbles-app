package com.bluebubbles.messaging.services.contentcapture

import android.app.Activity
import android.content.Context
import android.content.LocusId
import android.os.Build
import android.view.View
import android.view.ViewStructure
import android.view.autofill.AutofillId
import android.view.contentcapture.ContentCaptureContext
import android.view.contentcapture.ContentCaptureManager
import android.view.contentcapture.ContentCaptureSession
import androidx.annotation.RequiresApi
import com.bluebubbles.messaging.Constants
import com.bluebubbles.messaging.models.MethodCallHandlerImpl
import com.bluebubbles.messaging.utils.PersistentLog
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/// Mirrors the currently visible conversation into Android's content capture
/// subsystem so that on-device intelligence services (on One UI, Samsung's
/// `com.samsung.android.smartsuggestions`) can offer keyboard reply suggestions.
///
/// Flutter paints every message to a single surface, so the framework's automatic
/// content capture integration — which is driven by real [View] instances — sees
/// an empty screen. Instead we synthesize a *virtual* view tree describing the
/// thread and push it into the session ourselves. The intelligence service cannot
/// tell the difference between these nodes and ones emitted by a native TextView.
///
/// Two payloads are accepted:
///   `update-content-capture` — {chat_guid, messages: [{text, is_from_me, sender}]}
///   `clear-content-capture`  — {} (tears down the virtual tree on chat exit)
class ContentCaptureHandler: MethodCallHandlerImpl() {
    companion object {
        const val updateTag = "update-content-capture"
        const val clearTag = "clear-content-capture"

        /// Virtual ids are only required to be unique and stable within a session,
        /// so a monotonic counter is sufficient. Starts at 1 — 0 is reserved by the
        /// framework as "no virtual id".
        private var nextVirtualId = 1L

        /// The nodes currently believed to be on screen, so they can be retracted.
        private var liveVirtualIds = LongArray(0)
        private var liveHostId: AutofillId? = null

        /// Geometry is fabricated but must be *plausible*: the service infers
        /// message direction from horizontal placement the same way a human would.
        private const val VIEWPORT_WIDTH = 1080
        private const val ROW_HEIGHT = 140
        private const val BUBBLE_WIDTH = 620
        private const val INCOMING_LEFT = 40
        private const val OUTGOING_LEFT = VIEWPORT_WIDTH - BUBBLE_WIDTH - 40
    }

    override fun handleMethodCall(
        call: MethodCall,
        result: MethodChannel.Result,
        context: Context
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Content capture did not exist before API 29. Not an error — the
            // feature is simply unavailable, and Dart should not have to care.
            result.success(false)
            return
        }

        try {
            when (call.method) {
                clearTag -> {
                    clear(context)
                    result.success(true)
                }
                else -> {
                    val chatGuid = call.argument<String>("chat_guid")
                    if (chatGuid == null) {
                        result.error("CONTENT_CAPTURE_ERROR", "chat_guid is required", null)
                        return
                    }
                    val messages = call.argument<List<Map<String, Any?>>>("messages") ?: emptyList()
                    result.success(update(context, chatGuid, messages))
                }
            }
        } catch (e: Exception) {
            PersistentLog.e(context, Constants.logTag, "Content capture update failed", e)
            result.error("CONTENT_CAPTURE_ERROR", e.message, null)
        }
    }

    /// Returns true if the tree was handed to a live session, false if content
    /// capture is unavailable (service disabled, package not allowlisted, or the
    /// call arrived while no activity was in the foreground).
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun update(
        context: Context,
        chatGuid: String,
        messages: List<Map<String, Any?>>
    ): Boolean {
        val activity = context as? Activity ?: return false

        val manager = activity.getSystemService(ContentCaptureManager::class.java)
        if (manager == null || !manager.isContentCaptureEnabled) {
            // Either the OEM ships no intelligence service, the user disabled it,
            // or — most likely during development — this package is not on the
            // service's allowlist. `adb shell dumpsys content_capture` shows which.
            PersistentLog.d(context, Constants.logTag, "Content capture unavailable — skipping conversation mirror")
            return false
        }

        val host = activity.findViewById<View>(android.R.id.content) ?: return false
        val session = host.contentCaptureSession ?: return false
        val hostId = host.autofillId ?: return false

        // Retract the previous thread before drawing the new one, otherwise the
        // service accumulates stale messages across chat switches and suggests
        // replies to a conversation the user already left.
        clear(context)

        // Tie the capture to the same identifier the notification shortcut already
        // uses (see CreateIncomingMessageNotification's setShortcutId), which is how
        // the service correlates "this screen" with "this conversation".
        session.setContentCaptureContext(ContentCaptureContext.Builder(LocusId(chatGuid)).build())

        val emitted = ArrayList<Long>(messages.size + 1)

        messages.forEachIndexed { index, message ->
            val text = message["text"] as? String
            if (text.isNullOrBlank()) return@forEachIndexed
            val isFromMe = message["is_from_me"] as? Boolean ?: false
            val sender = message["sender"] as? String

            val virtualId = nextVirtualId++
            val node = session.newVirtualViewStructure(hostId, virtualId)
            node.setClassName("android.widget.TextView")
            // Sender attribution rides along in the node's text so the service can
            // attribute turns; native apps get this from separate name TextViews.
            node.setText(if (!isFromMe && sender != null) "$sender: $text" else text)
            node.setVisibility(View.VISIBLE)
            node.setDimens(
                if (isFromMe) OUTGOING_LEFT else INCOMING_LEFT,
                index * ROW_HEIGHT,
                0,
                0,
                BUBBLE_WIDTH,
                ROW_HEIGHT
            )
            session.notifyViewAppeared(node)
            emitted.add(virtualId)
        }

        // A composer node tells the service where a reply would be typed. Native
        // messaging apps always have one, and its absence may be enough for the
        // suggestion pipeline to decide this screen is not a conversation.
        val composerId = nextVirtualId++
        val composer: ViewStructure = session.newVirtualViewStructure(hostId, composerId)
        composer.setClassName("android.widget.EditText")
        composer.setText("")
        composer.setVisibility(View.VISIBLE)
        composer.setDimens(40, messages.size * ROW_HEIGHT, 0, 0, VIEWPORT_WIDTH - 80, ROW_HEIGHT)
        session.notifyViewAppeared(composer)
        emitted.add(composerId)

        liveVirtualIds = emitted.toLongArray()
        liveHostId = hostId

        PersistentLog.d(
            context,
            Constants.logTag,
            "Mirrored ${emitted.size - 1} messages to content capture for $chatGuid"
        )
        return true
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun clear(context: Context) {
        val hostId = liveHostId ?: return
        if (liveVirtualIds.isEmpty()) return

        val activity = context as? Activity
        val host = activity?.findViewById<View>(android.R.id.content)
        val session: ContentCaptureSession? = host?.contentCaptureSession
        session?.notifyViewsDisappeared(hostId, liveVirtualIds)

        liveVirtualIds = LongArray(0)
        liveHostId = null
    }
}
