package com.capturesms.keepalive

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object CallRecordingFinder {

    private val recordDirs = listOf(
        "/storage/emulated/0/MIUI/sound_recorder/call_rec",
        "/sdcard/MIUI/sound_recorder/call_rec",
        "/storage/emulated/0/Sounds/CallRecordings",
        "/storage/emulated/0/CallRecordings",
        "/sdcard/Sounds/CallRecordings",
        "/sdcard/CallRecordings"
    )

    fun findLatest(
        context: Context,
        startAt: Long,
        endAt: Long,
        number: String,
        lastPath: String
    ): Map<String, Any?>? {
        return findFromMediaStore(context, startAt, endAt, number, lastPath)
            ?: findFromDirs(startAt, endAt, number, lastPath)
    }

    private fun findFromMediaStore(
        context: Context,
        startAt: Long,
        endAt: Long,
        number: String,
        lastPath: String
    ): Map<String, Any?>? {
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE
        )

        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
            )
            buildBestCandidate(cursor, startAt, endAt, number, lastPath)
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun buildBestCandidate(
        cursor: Cursor?,
        startAt: Long,
        endAt: Long,
        number: String,
        lastPath: String
    ): Map<String, Any?>? {
        if (cursor == null) return null

        var best: RecordingCandidate? = null
        var count = 0
        while (cursor.moveToNext() && count < 80) {
            count++
            val name = cursor.getStringSafely(MediaStore.Audio.Media.DISPLAY_NAME).orEmpty()
            val relativePath = cursor.getStringSafely(MediaStore.Audio.Media.RELATIVE_PATH).orEmpty()
            val path = resolvePath(
                cursor.getStringSafely(MediaStore.Audio.Media.DATA).orEmpty(),
                relativePath,
                name
            )
            if (path.isEmpty() || path == lastPath || !isRecordingPath(path)) continue

            val modifiedAt = (cursor.getLongSafely(MediaStore.Audio.Media.DATE_MODIFIED) ?: 0L) * 1000L
            val size = cursor.getLongSafely(MediaStore.Audio.Media.SIZE) ?: 0L
            val candidate = buildCandidate(path, name, size, modifiedAt, startAt, endAt, number)
                ?: continue

            val current = best
            if (current == null || candidate.score > current.score ||
                (candidate.score == current.score && candidate.modifiedAt > current.modifiedAt)
            ) {
                best = candidate
            }
        }

        return best?.toMap()
    }

    private fun findFromDirs(
        startAt: Long,
        endAt: Long,
        number: String,
        lastPath: String
    ): Map<String, Any?>? {
        var best: RecordingCandidate? = null
        recordDirs.forEach { dirPath ->
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) return@forEach

            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val path = file.absolutePath
                if (path.isEmpty() || path == lastPath || !isSupportedAudio(path)) return@forEach

                val candidate = buildCandidate(
                    path = path,
                    name = file.name,
                    size = file.length(),
                    modifiedAt = file.lastModified(),
                    startAt = startAt,
                    endAt = endAt,
                    number = number
                ) ?: return@forEach

                val current = best
                if (current == null || candidate.score > current.score ||
                    (candidate.score == current.score && candidate.modifiedAt > current.modifiedAt)
                ) {
                    best = candidate
                }
            }
        }
        return best?.toMap()
    }

    private fun buildCandidate(
        path: String,
        name: String,
        size: Long,
        modifiedAt: Long,
        startAt: Long,
        endAt: Long,
        number: String
    ): RecordingCandidate? {
        val fileNumber = extractRecordingNumber(name)
        val fileTimestamp = extractRecordingTimestamp(name)
        val numberMatched = number.isNotEmpty() && fileNumber.contains(number)
        val timeAnchor = if (fileTimestamp > 0L) fileTimestamp else modifiedAt
        val withinWindow = timeAnchor in (startAt - 120_000L)..(endAt + 180_000L)
        if (!withinWindow && !numberMatched) return null

        return RecordingCandidate(
            path = path,
            name = name,
            size = size,
            modifiedAt = modifiedAt,
            fileTimestamp = fileTimestamp,
            score = scoreCandidate(modifiedAt, fileTimestamp, startAt, endAt, numberMatched)
        )
    }

    private fun scoreCandidate(
        modifiedAt: Long,
        fileTimestamp: Long,
        startAt: Long,
        endAt: Long,
        numberMatched: Boolean
    ): Int {
        val anchor = if (fileTimestamp > 0L) fileTimestamp else modifiedAt
        var score = 0
        if (numberMatched) score += 5000
        if (fileTimestamp > 0L) score += 1500
        score -= ((kotlin.math.abs(anchor - endAt)) / 1000L).toInt()
        score -= ((kotlin.math.abs(anchor - startAt)) / 5000L).toInt()
        return score
    }

    private fun extractRecordingNumber(name: String): String {
        val prefixMatch = Regex("^(\\d{6,})").find(name)?.groupValues?.getOrNull(1)
        return if (!prefixMatch.isNullOrEmpty()) prefixMatch else PhoneUtils.normalizePhoneNumber(name)
    }

    private fun extractRecordingTimestamp(name: String): Long {
        val raw = Regex("_(\\d{14})(?:\\.[^.]+)?$").find(name)?.groupValues?.getOrNull(1) ?: return 0L
        return try {
            val parser = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
            parser.parse(raw)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun isRecordingPath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase(Locale.ROOT)
        return normalized.contains("/miui/sound_recorder/call_rec") ||
            normalized.contains("/sounds/callrecordings") ||
            normalized.contains("/callrecordings")
    }

    private fun resolvePath(path: String, relativePath: String, name: String): String {
        if (path.isNotEmpty()) return path
        if (relativePath.isEmpty() || name.isEmpty()) return ""
        val base = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val relative = relativePath.replace('\\', '/').trim('/')
        return "$base/$relative/$name"
    }

    private fun isSupportedAudio(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.endsWith(".mp3") ||
            lower.endsWith(".m4a") ||
            lower.endsWith(".amr") ||
            lower.endsWith(".aac") ||
            lower.endsWith(".wav")
    }

    private data class RecordingCandidate(
        val path: String,
        val name: String,
        val size: Long,
        val modifiedAt: Long,
        val fileTimestamp: Long,
        val score: Int
    ) {
        fun toMap(): Map<String, Any?> {
            return mapOf(
                "path" to path,
                "name" to name,
                "size" to size,
                "modifiedAt" to modifiedAt,
                "fileTimestamp" to fileTimestamp,
                "score" to score
            )
        }
    }

    private fun Cursor.getStringSafely(columnName: String): String? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private fun Cursor.getLongSafely(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getLong(index)
    }
}
