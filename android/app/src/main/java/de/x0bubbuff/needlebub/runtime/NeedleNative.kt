package de.x0bubbuff.needlebub.runtime

object NeedleNative {
    init {
        System.loadLibrary("needlebub_runtime")
    }

    /** Build-time engine ABI pin from vendor/needle/UPSTREAM.json. */
    external fun nativeAbi(): String

    /** Returns a negative code before any native model work when the descriptor or archive is rejected (not a regular file of the declared size, or not a Needle 3 archive). */
    external fun load(fd: Int, size: Long): Int

    /** Prompt and tools are real UTF-8 bytes, not JNI modified UTF-8. */
    external fun initialize(systemPrompt: ByteArray, toolsJson: ByteArray): Int

    /** Reply bytes are real UTF-8, bounded by [capacity]; null on failure or wrong order. */
    external fun complete(input: ByteArray, maxTokens: Int, capacity: Int): ByteArray?

    /**
     * Rewinds conversation state of the already mapped model.
     *
     * Independent requests must not share a conversation: upstream Needle 3 keeps turn
     * history until `needle_reset()` is called, and a second independent request over a
     * retained conversation can run away until the caller kills the process. This call
     * only rewinds; it never unmaps the model or drops the loaded pack.
     */
    external fun rewind()

    /** Releases the mapped model. Teardown and pack changes only, never per request. */
    external fun reset()
}
