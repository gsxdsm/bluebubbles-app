package com.bluebubbles.messaging.services.system

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.bluebubbles.messaging.Constants
import com.bluebubbles.messaging.MainActivity
import com.bluebubbles.messaging.models.MethodCallHandlerImpl
import com.bluebubbles.messaging.utils.PersistentLog
import com.bluebubbles.messaging.utils.Utils
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/// Create android share sheet targets
class PushShareTargetsHandler: MethodCallHandlerImpl() {
    companion object {
        const val tag = "push-share-targets"
    }

    override fun handleMethodCall(
        call: MethodCall,
        result: MethodChannel.Result,
        context: Context
    ) {
        val name: String = call.argument("title")!!
        val guid: String = call.argument("guid")!!
        val icon: ByteArray? = call.argument("icon")
        pushShareTarget(context, name, guid, icon)
        result.success(null)
    }

    fun pushShareTarget(context: Context, name: String, guid: String, icon: ByteArray?) {
        val adaptiveIcon = if ((icon?.size ?: 0) == 0) null else Utils.getAdaptiveIconFromByteArray(icon!!)

        PersistentLog.d(context, Constants.logTag, "Creating intent for shortcut with name $name")
        val contactCategories = setOf(Constants.categoryTextShareTarget)
        // Target the package's real launcher activity rather than MainActivity by class.
        // For most flavors these are the same, but the `smartsuggest` flavor's launcher is
        // a differently-named subclass (com.whatsapp.Conversation) so One UI's content
        // capture allowlist opens a session for it. A shortcut hardcoded to MainActivity
        // would land on the non-allowlisted component and get no keyboard suggestions.
        val launcherComponent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
        val launcherIntent = Intent(Intent.ACTION_DEFAULT)
            .putExtra("chatGuid", guid)
            .putExtra("bubble", false)
        if (launcherComponent != null) {
            launcherIntent.component = launcherComponent
        } else {
            launcherIntent.setClass(context, MainActivity::class.java)
        }
        val person = Person.Builder().setName(name)
        if (adaptiveIcon != null) {
            person.setIcon(adaptiveIcon)
        }

        PersistentLog.d(context, Constants.logTag, "Creating and pushing shortcut for $name")
        val shortcut = ShortcutInfoCompat.Builder(context, guid)
            .setShortLabel(name)
            .setIntent(launcherIntent)
            .setCategories(contactCategories)
            .setLongLived(true)
            .setIsConversation()
            // Ties this shortcut to the same locus the conversation view reports to the
            // content capture subsystem (see ContentCaptureHandler), which is how the
            // system correlates "the screen being captured" with "this conversation".
            .setLocusId(LocusIdCompat(guid))
            .setPerson(person.build())
        if (adaptiveIcon != null) {
            shortcut.setIcon(adaptiveIcon)
        }

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut.build())
    }
}