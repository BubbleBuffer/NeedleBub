package de.x0bubbuff.needlebub.runtime

object NeedleNative {
    init {
        System.loadLibrary("needlebub_runtime")
    }

    external fun load(fd: Int, size: Long): Int
    external fun initialize(systemPrompt: String, toolsJson: String): Int
    external fun complete(input: String, maxTokens: Int, capacity: Int): String?

    /**
     * Rewinds conversation state of the already mapped model.
     *
     * Independent requests must not share a conversation: upstream Needle2 keeps turn
     * history until `needle_reset()` is called, and a second independent request over a
     * retained conversation can run away until the caller kills the process. This call
     * only rewinds; it never unmaps the model or drops the loaded pack.
     */
    external fun rewind()

    /** Releases the mapped model. Teardown and pack changes only, never per request. */
    external fun reset()
}
