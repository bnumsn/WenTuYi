package com.wentuyi.app

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class CameraLifecycleTest {
    @Test fun delayedOpenAndFrameCallbacksCannotAttachToAResumedCamera() {
        val complete = CountDownLatch(1)
        val staleAccepted = AtomicBoolean(true)
        val currentAccepted = AtomicBoolean(false)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val guard = CameraLifecycleGuard()
            guard.resume()
            val oldOpen = guard.token()
            // Queue the same ownership guard used by device/session/frame callbacks.
            Handler(Looper.getMainLooper()).post {
                staleAccepted.set(guard.accepts(oldOpen))
                currentAccepted.set(guard.accepts(guard.token()))
                complete.countDown()
            }
            guard.pause()
            assertFalse(guard.accepts(oldOpen))
            guard.resume()
        }
        assertTrue(complete.await(5, TimeUnit.SECONDS))
        assertFalse("old callbacks must close/ignore their own resource", staleAccepted.get())
        assertTrue("the new camera is still active", currentAccepted.get())
    }

    @Test fun destroyedPreviewSurfaceInvalidatesOnlyTheOldOpenCycle() {
        val guard = CameraLifecycleGuard()
        guard.resume()
        val oldOpen = guard.token()
        guard.invalidate()
        assertFalse(guard.accepts(oldOpen))
        assertTrue(guard.accepts(guard.token()))
        guard.pause()
        assertFalse(guard.accepts(guard.token()))
    }
}
