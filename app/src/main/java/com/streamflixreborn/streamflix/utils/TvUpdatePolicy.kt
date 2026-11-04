package com.streamflixreborn.streamflix.utils

/** Android TV is the only installable build and therefore the only update asset. */
object TvUpdatePolicy {
    const val APK_MIME = "application/vnd.android.package-archive"

    fun isTvUpdate(name: String, contentType: String): Boolean =
        name.endsWith("-tv.apk", ignoreCase = true) && contentType.equals(APK_MIME, ignoreCase = true)

    fun compare(left: String, right: String): Int {
        val a = left.split('.').map { it.toLongOrNull() ?: 0L }
        val b = right.split('.').map { it.toLongOrNull() ?: 0L }
        for (index in 0 until maxOf(a.size, b.size)) {
            val result = a.getOrElse(index) { 0L }.compareTo(b.getOrElse(index) { 0L })
            if (result != 0) return result
        }
        return 0
    }
}
