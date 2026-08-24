package de.x0bubbuff.needlebub.runtime

object NeedleNative {
    init {
        System.loadLibrary("needlebub_runtime")
    }

    external fun load(fd: Int, size: Long): Int
    external fun initialize(systemPrompt: String, toolsJson: String): Int
    external fun complete(input: String, maxTokens: Int, capacity: Int): String?
    external fun reset()
}
