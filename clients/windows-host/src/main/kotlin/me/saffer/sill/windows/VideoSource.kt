package me.saffer.sill.windows

import me.saffer.sill.core.ParameterSets

/**
 * Supplies the video for one captured source (phase 1: the Desktop only). Implementations call back
 * on their own thread; [stop] must be safe to call more than once and from any thread.
 */
interface VideoSource {
    fun start(onParams: (ParameterSets) -> Unit, onFrame: (keyframe: Boolean, lengthPrefixed: ByteArray) -> Unit)
    /** The device asked for a fresh keyframe (kind 44): force an IDR. Phase 1: restart the encoder. */
    fun requestKeyframe()
    fun stop()
}

/** A fixed loop of frames, for tests: no ffmpeg, no GPU, no screen. */
class FakeVideoSource(
    private val sets: ParameterSets,
    private val frames: List<Pair<Boolean, ByteArray>>,
    private val intervalMs: Long = 16,
) : VideoSource {
    @Volatile private var thread: Thread? = null
    @Volatile private var keyframeRequested = false

    override fun start(onParams: (ParameterSets) -> Unit, onFrame: (Boolean, ByteArray) -> Unit) {
        thread = Thread({
            try {
                onParams(sets)
                while (!Thread.currentThread().isInterrupted) {
                    for ((key, au) in frames) {
                        if (Thread.currentThread().isInterrupted) return@Thread
                        onFrame(key || keyframeRequested, au)
                        keyframeRequested = false
                        Thread.sleep(intervalMs)
                    }
                }
            } catch (_: InterruptedException) {}
        }, "fake-video").apply { isDaemon = true; start() }
    }

    override fun requestKeyframe() { keyframeRequested = true }
    override fun stop() { thread?.interrupt(); thread = null }
}
