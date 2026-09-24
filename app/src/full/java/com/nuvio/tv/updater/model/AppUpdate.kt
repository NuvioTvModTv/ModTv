package com.nuvio.tv.updater.model

data class AppUpdate(
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val versionCode: Int = 0,
    val sha256: String = "",
    val assetSizeBytes: Long?
)
