package com.nova.callassist.speech

/**
 * Supported on-device quantized Whisper speech models.
 * Compatible with whisper.cpp and quantized in Q5_1 format for maximum on-device CPU efficiency.
 */
enum class WhisperModelOption(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val approxSizeMb: Int,
    val description: String
) {
    TINY_EN(
        id = "tiny_en",
        displayName = "Whisper Tiny English (Q5_1)",
        fileName = "ggml-tiny.en-q5_1.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin",
        expectedSizeBytes = 32166155L,
        approxSizeMb = 31,
        description = "Fastest on-device model. Ultra-low latency and battery consumption. Recommended for everyday calling commands."
    ),
    BASE_EN(
        id = "base_en",
        displayName = "Whisper Base English (Q5_1)",
        fileName = "ggml-base.en-q5_1.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin",
        expectedSizeBytes = 59721011L,
        approxSizeMb = 57,
        description = "Higher accuracy on diverse regional and Indian English accents (~57 MB). Slightly higher RAM and CPU usage."
    );

    companion object {
        val DEFAULT = TINY_EN

        fun fromId(id: String): WhisperModelOption =
            values().firstOrNull { it.id == id } ?: DEFAULT

        fun fromFileName(fileName: String): WhisperModelOption? =
            values().firstOrNull { it.fileName == fileName }
    }
}
