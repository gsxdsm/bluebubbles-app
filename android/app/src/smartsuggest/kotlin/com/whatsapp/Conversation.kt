package com.whatsapp

/// Launcher activity for the `smartsuggest` flavor, named to satisfy both gates One UI's
/// SmartSuggestions service applies (verified by decompiling the service):
///
///   1. Content-capture allowlist — which activity opens a capture session. The live
///      entry for WhatsApp / WhatsApp Business is `<pkg>/com.whatsapp.Conversation`.
///   2. Reply parser (deepscreencapture → smartreply) — which activity counts as a
///      chatroom: Constants.ACTIVITY_NAMES.WHATSAPP_CONVERSATION_ACTIVITY =
///      "com.whatsapp.Conversation".
///
/// Both are the same `com.whatsapp.Conversation` component, so one launcher activity
/// satisfies both. (Signal, the first target, splits these across MainActivity and
/// ConversationActivity, so no single activity could satisfy it.)
///
/// The actual conversation content the service reads is the virtual view tree
/// ContentCaptureHandler emits onto the FlutterView — shaped to match WhatsApp's real
/// view hierarchy (a `conversation_list` thread, `message_text` rows, a
/// `conversation_contact_name` title). Behaviour here is the real Flutter activity; only
/// the component name differs.
class Conversation : com.bluebubbles.messaging.MainActivity()
