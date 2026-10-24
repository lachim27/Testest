package pl.zlapegzamin.test

import java.lang.ref.WeakReference

/**
 * The foreground service may ask the EXISTING authenticated WebView to run
 * its session-keepalive script while the Activity is minimized.
 * No Activity, WebView, cookies, passwords or request bodies are retained here.
 * All calls happen on the main thread.
 */
internal object PortalMonitorBridge {
    private var current: WeakReference<SlotMonitor>? = null

    fun attach(monitor: SlotMonitor) {
        current = WeakReference(monitor)
    }

    fun detach(monitor: SlotMonitor) {
        if (current?.get() === monitor) current = null
    }

    fun pulse(): Boolean = current?.get()?.pulseFromForegroundService() ?: false

    fun attached(): Boolean = current?.get() != null
}
