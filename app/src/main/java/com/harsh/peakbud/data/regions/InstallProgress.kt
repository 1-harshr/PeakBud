package com.harsh.peakbud.data.regions

/** Data-layer progress has no display language. Android presentation owns the copy. */
enum class InstallStage { DOWNLOAD, CHECK_TERRAIN, PREPARE, SAVE, CHECK_FILES }
data class InstallProgress(val stage: InstallStage, val name: String = "", val current: Int = 0, val total: Int = 0,
                           val receivedBytes: Long = 0, val downloadBytes: Long = 0)
