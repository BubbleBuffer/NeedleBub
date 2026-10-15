package de.x0bubbuff.needlebub.runtime

/**
 * Native engine seam.
 *
 * Split out so the conversation policy can be regression tested on the JVM without the
 * device: [rewind] must rewind the conversation of the mapped model, while [reset] is the
 * heavyweight path that releases and unmaps the model.
 */
interface RuntimeEngine {
    fun load(fd: Int, size: Long): Int

    fun initialize(systemPrompt: String, toolsJson: String): Int

    fun complete(input: String, maxTokens: Int, capacity: Int): String?

    /** Rewinds conversation state only: mapped model, tools and pack stay resident. */
    fun rewind()

    /** Releases the mapped model; only for teardown, pack change or a hard recycle. */
    fun reset()
}

object NeedleNativeEngine : RuntimeEngine {
    override fun load(fd: Int, size: Long): Int = NeedleNative.load(fd, size)

    override fun initialize(systemPrompt: String, toolsJson: String): Int =
        NeedleNative.initialize(systemPrompt, toolsJson)

    override fun complete(input: String, maxTokens: Int, capacity: Int): String? =
        NeedleNative.complete(input, maxTokens, capacity)

    override fun rewind() = NeedleNative.rewind()

    override fun reset() = NeedleNative.reset()
}
