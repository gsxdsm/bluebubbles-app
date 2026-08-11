package com.bluebubbles.messaging.services.contentcapture

import android.app.Activity
import android.content.Context
import android.content.LocusId
import android.os.Build
import android.view.View
import android.view.ViewGroup
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

        /// Identity of the app whose allowlist slot this build occupies (see the
        /// `smartsuggest` product flavor). SmartSuggestions' ScreenContentManager reads a
        /// per-app policy that maps the package to the resource id entries of its message,
        /// list and title views, so nodes are stamped with WhatsApp's resource entry names.
        ///
        /// These are WhatsApp's real conversation view ids; the message row text view is
        /// `message_text`, the list is `conversation` (a RecyclerView), the toolbar title
        /// is `conversation_contact_name`. If the impersonated package in the flavor
        /// changes, these must change with it.
        private const val IMPERSONATED_PACKAGE = "com.whatsapp"
        private const val BODY_ID = "message_text"
        private const val BODY_CLASS = "android.widget.TextView"
        private const val COMPOSER_ID = "entry"
        private const val COMPOSER_CLASS = "android.widget.EditText"

        /// The message list container. LiveTranslationParser.getConversationThreadView
        /// (decompiled) looks for a node whose class is a threading class
        /// (RecyclerView/ListView), whose idEntry is in listviewIdEntries, that has an
        /// autofill id, and that is "large enough to contain a conversation". Messages are
        /// then matched by idEntry and attributed to sender purely by their Rect position
        /// relative to this thread's Rect (isSent: right-of-centre = you, left = them).
        private const val THREAD_ID = "conversation_list"
        private const val THREAD_CLASS = "android.widget.ListView"
        private const val TITLE_ID = "conversation_contact_name"

        /// Fabricated but internally consistent geometry. The parser only compares message
        /// Rects against the thread Rect, so what matters is: messages sit inside the
        /// thread, received on the left half, sent on the right half.
        private const val VIEWPORT_WIDTH = 1080
        private const val THREAD_TOP = 200
        private const val THREAD_BOTTOM = 2200
        private const val ROW_HEIGHT = 150
        private const val BUBBLE_WIDTH = 560
        private const val INCOMING_LEFT = 24
        private const val OUTGOING_LEFT = VIEWPORT_WIDTH - BUBBLE_WIDTH - 24
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
                    val title = call.argument<String>("title")
                    result.success(update(context, chatGuid, title, messages))
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
        title: String?,
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

        // Hang the virtual tree off the view the input method is actually serving — the
        // FlutterView. The keyboard binds its suggestions to the served view, so nodes
        // parented anywhere else (previously android.R.id.content) describe a subtree the
        // suggestion pipeline has no reason to associate with the field being typed into.
        val host = findFlutterView(activity.findViewById(android.R.id.content))
            ?: activity.findViewById<View>(android.R.id.content)
            ?: return false
        val session = host.contentCaptureSession ?: return false
        val hostId = host.autofillId ?: return false
        PersistentLog.d(context, Constants.logTag, "Content capture host view: ${host.javaClass.name}")

        // Retract the previous thread before drawing the new one, otherwise the
        // service accumulates stale messages across chat switches and suggests
        // replies to a conversation the user already left.
        clear(context)

        // Tie the capture to the same identifier the notification shortcut already
        // uses (see CreateIncomingMessageNotification's setShortcutId), which is how
        // the service correlates "this screen" with "this conversation".
        session.setContentCaptureContext(ContentCaptureContext.Builder(LocusId(chatGuid)).build())

        val emitted = ArrayList<Long>(messages.size + 3)

        // Title node — the parser reads the conversation name from a node whose idEntry is
        // in titleIdEntries (isTitleView). Without it, enterChatRoom has no title.
        if (!title.isNullOrBlank()) {
            val titleId = nextVirtualId++
            val titleNode = session.newVirtualViewStructure(hostId, titleId)
            titleNode.setClassName(BODY_CLASS)
            titleNode.setId(titleId.toInt(), IMPERSONATED_PACKAGE, "id", TITLE_ID)
            titleNode.setText(title)
            titleNode.setVisibility(View.VISIBLE)
            titleNode.setDimens(160, 40, 0, 0, VIEWPORT_WIDTH - 320, 120)
            session.notifyViewAppeared(titleNode)
            emitted.add(titleId)
        }

        // Thread container — the message list. getConversationThreadView requires a node
        // whose class is a threading class (ListView/RecyclerView), whose idEntry is in
        // listviewIdEntries, that has an autofill id, and is large enough. Every message
        // is attributed to a sender by its Rect relative to THIS node's Rect.
        val threadId = nextVirtualId++
        val threadNode = session.newVirtualViewStructure(hostId, threadId)
        threadNode.setClassName(THREAD_CLASS)
        threadNode.setId(threadId.toInt(), IMPERSONATED_PACKAGE, "id", THREAD_ID)
        threadNode.setVisibility(View.VISIBLE)
        threadNode.setDimens(0, THREAD_TOP, 0, 0, VIEWPORT_WIDTH, THREAD_BOTTOM - THREAD_TOP)
        session.notifyViewAppeared(threadNode)
        emitted.add(threadId)

        // Message rows — text-only nodes with the message idEntry. Direction is conveyed
        // purely by geometry (received left, sent right); the text is the raw message.
        messages.forEachIndexed { index, message ->
            val text = message["text"] as? String
            if (text.isNullOrBlank()) return@forEachIndexed
            val isFromMe = message["is_from_me"] as? Boolean ?: false

            val virtualId = nextVirtualId++
            val node = session.newVirtualViewStructure(hostId, virtualId)
            node.setClassName(BODY_CLASS)
            node.setId(virtualId.toInt(), IMPERSONATED_PACKAGE, "id", BODY_ID)
            node.setText(text)
            node.setVisibility(View.VISIBLE)
            val top = THREAD_TOP + 24 + index * ROW_HEIGHT
            node.setDimens(
                if (isFromMe) OUTGOING_LEFT else INCOMING_LEFT,
                top,
                0,
                0,
                BUBBLE_WIDTH,
                ROW_HEIGHT - 24
            )
            session.notifyViewAppeared(node)
            emitted.add(virtualId)
        }

        // Composer — where a reply would be typed.
        val composerId = nextVirtualId++
        val composer: ViewStructure = session.newVirtualViewStructure(hostId, composerId)
        composer.setClassName(COMPOSER_CLASS)
        composer.setId(composerId.toInt(), IMPERSONATED_PACKAGE, "id", COMPOSER_ID)
        composer.setText("")
        composer.setVisibility(View.VISIBLE)
        composer.setDimens(40, THREAD_BOTTOM + 40, 0, 0, VIEWPORT_WIDTH - 80, ROW_HEIGHT)
        session.notifyViewAppeared(composer)
        emitted.add(composerId)

        liveVirtualIds = emitted.toLongArray()
        liveHostId = hostId

        PersistentLog.d(
            context,
            Constants.logTag,
            "Mirrored ${messages.size} messages (thread=$THREAD_ID title=${!title.isNullOrBlank()}) to content capture for $chatGuid"
        )
        return true
    }

    /// Depth-first search for the FlutterView, which is what the input method serves
    /// (confirmed via `dumpsys input_method`: mServedView=io.flutter.embedding.android.FlutterView).
    private fun findFlutterView(root: View?): View? {
        if (root == null) return null
        if (root.javaClass.name.contains("FlutterView")) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            findFlutterView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun clear(context: Context) {
        val hostId = liveHostId ?: return
        if (liveVirtualIds.isEmpty()) return

        val activity = context as? Activity
        val content = activity?.findViewById<View>(android.R.id.content)
        // Must retract through the same view the nodes were emitted under.
        val host = findFlutterView(content) ?: content
        val session: ContentCaptureSession? = host?.contentCaptureSession
        session?.notifyViewsDisappeared(hostId, liveVirtualIds)

        liveVirtualIds = LongArray(0)
        liveHostId = null
    }
}
