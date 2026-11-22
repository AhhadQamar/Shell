package app.minimalshell

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Lets Shell do the two things a launcher cannot do on its own:
 * lock the screen and pull down the notification shade.
 *
 * It deliberately requests no event types and cannot read the screen,
 * so it can only perform these global actions on request.
 */
class ShellAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        running = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
            running = false
        }

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            running = false
        }

        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event types are requested.
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile
        private var instance: ShellAccessibilityService? = null

        /** True while the service is enabled. */
        var running by mutableStateOf(false)
            private set

        /** Locks the screen on Android 9 and newer. */
        fun lockScreen(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                instance?.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                ) == true

        /** Opens the notification shade. */
        fun openShade(): Boolean =
            instance?.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            ) == true
    }
}
