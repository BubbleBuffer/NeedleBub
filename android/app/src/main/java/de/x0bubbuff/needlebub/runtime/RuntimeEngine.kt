package de.x0bubbuff.needlebub.runtime

import de.x0bubbuff.needlebub.packs.PackManifest

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
    /** Returned by [load] when the native engine ABI differs from the pack's engine ABI. */
    const val ABI_MISMATCH = -10

    override fun load(fd: Int, size: Long): Int {
        if (NeedleNative.nativeAbi() != PackManifest.ENGINE_ABI) return ABI_MISMATCH
        return NeedleNative.load(fd, size)
    }

    override fun initialize(systemPrompt: String, toolsJson: String): Int =
        NeedleNative.initialize(systemPrompt.toByteArray(Charsets.UTF_8), toolsJson.toByteArray(Charsets.UTF_8))

    override fun complete(input: String, maxTokens: Int, capacity: Int): String? =
        NeedleNative.complete(input.toByteArray(Charsets.UTF_8), maxTokens, capacity)?.toString(Charsets.UTF_8)

    override fun rewind() = NeedleNative.rewind()

    override fun reset() = NeedleNative.reset()
}
