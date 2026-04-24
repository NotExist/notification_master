package com.notificationmaster.data.filter

enum class OrderBy {
    PostTimeDesc,
    PostTimeAsc,
    CaptureTimeDesc;

    companion object {
        fun fromName(name: String): OrderBy = values().firstOrNull { it.name == name } ?: PostTimeDesc
    }
}
