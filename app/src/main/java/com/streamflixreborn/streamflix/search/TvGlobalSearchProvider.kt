package com.streamflixreborn.streamflix.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.streamflixreborn.streamflix.profiles.KidsContentFilter
import com.streamflixreborn.streamflix.profiles.TvProfileRepository
import com.streamflixreborn.streamflix.stremio.StremioAddonsClient
import com.streamflixreborn.streamflix.stremio.StremioMeta
import com.streamflixreborn.streamflix.tv.model.TvMediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Native Android TV Global Voice Search `ContentProvider`.
 * Surfaces movie and series results directly on the global Android TV launcher when the user
 * presses the remote microphone button (e.g., saying "Ver Interstellar").
 */
class TvGlobalSearchProvider : ContentProvider() {

    companion object {
        private const val SEARCH_TIMEOUT_MS = 3_200L

        private val SUGGEST_COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_IS_LIVE,
            SearchManager.SUGGEST_COLUMN_VIDEO_WIDTH,
            SearchManager.SUGGEST_COLUMN_VIDEO_HEIGHT,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
            SearchManager.SUGGEST_COLUMN_DURATION,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
        )
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(SUGGEST_COLUMNS)
        val rawQuery = selectionArgs?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf {
                it.isNotBlank() && !it.equals(SearchManager.SUGGEST_URI_PATH_QUERY, ignoreCase = true)
            }
            ?: return cursor

        val normalizedQuery = TvVoiceQueryNormalizer.normalizeSpokenQuery(rawQuery)
        if (normalizedQuery.isBlank()) return cursor

        val ctx = context
        val isKidsMode = runCatching {
            if (ctx != null) TvProfileRepository.isLastActiveProfileKids(ctx) else false
        }.getOrDefault(false)

        val matchedMetas: List<Pair<TvMediaKind, StremioMeta>> = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                coroutineScope {
                    val nuvioMoviesDef = async { StremioAddonsClient.searchNuvio("movie", normalizedQuery) }
                    val nuvioSeriesDef = async { StremioAddonsClient.searchNuvio("series", normalizedQuery) }
                    val cinemetaMoviesDef = async { StremioAddonsClient.searchCinemeta("movie", normalizedQuery) }
                    val cinemetaSeriesDef = async { StremioAddonsClient.searchCinemeta("series", normalizedQuery) }

                    val movies = mergeMetas(nuvioMoviesDef.await(), cinemetaMoviesDef.await())
                        .map { TvMediaKind.MOVIE to it }
                    val series = mergeMetas(nuvioSeriesDef.await(), cinemetaSeriesDef.await())
                        .map { TvMediaKind.SERIES to it }

                    interleavePairs(movies, series)
                        .filter { (_, meta) ->
                            meta.name.isNotBlank() &&
                                (!meta.poster.isNullOrBlank() || !meta.background.isNullOrBlank()) &&
                                (!isKidsMode || KidsContentFilter.isMetaAllowedForKids(meta))
                        }
                        .take(18)
                }
            }.orEmpty()
        }

        matchedMetas.forEachIndexed { idx, (kind, meta) ->
            val primaryId = meta.resolvedImdbId ?: meta.id
            StremioAddonsClient.recordHint(primaryId, meta, null)

            val subtitleParts = buildList {
                add(if (kind == TvMediaKind.SERIES) "Serie" else "Película")
                meta.resolvedYear?.let { add(it) }
                meta.resolvedRating?.takeIf { it > 0.0 }?.let {
                    add(String.format(Locale.US, "★ %.1f", it))
                }
            }
            val yearInt = meta.resolvedYear?.toIntOrNull() ?: 2024
            val durationMs = (meta.resolvedRuntimeMinutes?.toLong()?.times(60_000L))
                ?.takeIf { it > 0L }
                ?: if (kind == TvMediaKind.SERIES) 2_700_000L else 7_200_000L

            val dataId = TvVoiceQueryNormalizer.buildSuggestDataId(
                kind = kind,
                contentId = primaryId,
                imdbId = meta.resolvedImdbId,
                title = meta.name,
            )

            cursor.addRow(
                arrayOf<Any?>(
                    idx.toLong(),
                    meta.name,
                    subtitleParts.joinToString(" · "),
                    meta.poster ?: meta.background,
                    "video/mp4",
                    0,
                    1920,
                    1080,
                    yearInt,
                    durationMs,
                    "android.intent.action.VIEW",
                    dataId,
                )
            )
        }

        return cursor
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun mergeMetas(
        nuvio: List<StremioMeta>,
        cinemeta: List<StremioMeta>,
    ): List<StremioMeta> {
        val seen = HashSet<String>()
        val result = ArrayList<StremioMeta>(nuvio.size + cinemeta.size)
        for (item in nuvio + cinemeta) {
            val key = item.resolvedImdbId ?: item.id
            if (key.isNotBlank() && seen.add(key)) {
                result.add(item)
            }
        }
        return result
    }

    private fun <T> interleavePairs(a: List<T>, b: List<T>): List<T> {
        val max = maxOf(a.size, b.size)
        val out = ArrayList<T>(a.size + b.size)
        for (i in 0 until max) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
        }
        return out
    }
}
