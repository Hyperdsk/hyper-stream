package com.lagradost.cloudstream4.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the display awake, with no screensaver or display sleep, while a video plays. VLC does
 * this only for windows of its own; here the app draws the frames, so to the system a film looks
 * like an idle computer. Paused, the screen can sleep again.
 */
object ScreenAwake {
    private val os = System.getProperty("os.name").lowercase()

    // One thread for every call: Windows keeps the request per thread, so it must be ended on
    // the thread that made it
    private val thread = Executors.newSingleThreadScheduledExecutor { Thread(it, "screen-awake").apply { isDaemon = true } }
    private var on = false
    private var macAssertion: Int? = null
    private var linuxReset: ScheduledFuture<*>? = null

    /** Whether the display is being kept awake, for tests. */
    @Volatile var active = false
        private set

    fun set(awake: Boolean) {
        thread.execute {
            if (awake == on) return@execute
            on = awake
            runCatching {
                when {
                    os.contains("mac") -> mac(awake)
                    os.contains("win") -> windows(awake)
                    else -> linux(awake)
                }
            }.onFailure { System.err.println("Couldn't ${if (awake) "keep the screen awake" else "let the screen sleep"}: $it") }
            active = awake
        }
    }

    /** Waits for earlier [set] calls to take effect, for tests. */
    fun settle() = thread.submit {}.get(5, TimeUnit.SECONDS)

    // ---------- macOS: an IOKit power assertion, as QuickTime takes while playing ----------

    @Suppress("FunctionName")
    private interface IOKit : Library {
        fun IOPMAssertionCreateWithName(type: Pointer, level: Int, name: Pointer, id: IntByReference): Int
        fun IOPMAssertionRelease(id: Int): Int
    }

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFStringCreateWithCString(allocator: Pointer?, text: String, encoding: Int): Pointer
        fun CFRelease(value: Pointer)
    }

    private val ioKit by lazy { Native.load("IOKit", IOKit::class.java) }
    private val coreFoundation by lazy { Native.load("CoreFoundation", CoreFoundation::class.java) }

    private fun mac(awake: Boolean) {
        if (awake) {
            val utf8 = 0x08000100
            val type = coreFoundation.CFStringCreateWithCString(null, "PreventUserIdleDisplaySleep", utf8)
            val name = coreFoundation.CFStringCreateWithCString(null, "Hyper Stream is playing a video", utf8)
            try {
                val id = IntByReference()
                // 255: kIOPMAssertionLevelOn; 0: kIOReturnSuccess
                if (ioKit.IOPMAssertionCreateWithName(type, 255, name, id) == 0) macAssertion = id.value
            } finally {
                coreFoundation.CFRelease(type)
                coreFoundation.CFRelease(name)
            }
        } else {
            macAssertion?.let { ioKit.IOPMAssertionRelease(it) }
            macAssertion = null
        }
    }

    // ---------- Windows ----------

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun SetThreadExecutionState(flags: Int): Int
    }

    private val kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }

    private fun windows(awake: Boolean) {
        val continuous = 0x80000000.toInt()
        val systemRequired = 0x1
        val displayRequired = 0x2
        kernel32.SetThreadExecutionState(if (awake) continuous or systemRequired or displayRequired else continuous)
    }

    // ---------- Linux: no single API across desktops, so the screensaver's timer is reset ----------

    private fun linux(awake: Boolean) {
        linuxReset?.cancel(false)
        linuxReset = null
        if (awake) {
            linuxReset = thread.scheduleWithFixedDelay({
                runCatching { ProcessBuilder("xdg-screensaver", "reset").redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS) }
            }, 0, 50, TimeUnit.SECONDS)
        }
    }
}
