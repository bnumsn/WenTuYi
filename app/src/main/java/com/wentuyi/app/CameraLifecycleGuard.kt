package com.wentuyi.app

/** Camera callbacks from an older open/pause cycle may only close their own resources. */
internal class CameraLifecycleGuard {
    @Volatile private var generation = 0L
    @Volatile private var resumed = false

    fun resume() { generation++; resumed = true }
    fun pause() { resumed = false; generation++ }
    fun invalidate() { generation++ }
    fun token(): Long = generation
    fun accepts(token: Long): Boolean = resumed && token == generation
}
