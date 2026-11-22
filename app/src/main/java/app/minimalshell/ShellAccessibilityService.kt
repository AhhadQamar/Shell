package app.minimalshell

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Lets Shell do the two things a launcher can't do on its own: lock the screen and pull down the notification shade.
 *
 * It is deliberately as small as an accessibility service can be: it subscribes to no events and cannot read
 * the screen (see res/xml/accessibility_service_config.xml), so it can only perform these global actions on request.
 * The user switches it on in the system's accessibility settings; Shell works fine without it.
 */
class ShellAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        running = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) { instance = null; running = false }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) { instance = null; running = false }
        super.onDestroy()
    }

    // no event types are requested, so there is nothing to receive
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        @Volatile private var instance: ShellAccessibilityService? = null

        /** True while the user has the service switched on. Observable, so the settings row updates by itself. */
        var running by mutableStateOf(false)
            private set

        /** Locks the screen (Android 9+). Returns false if the service is off or the system refused. */
       fun lockScreen(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        instance?.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        ) == true
    }
}
