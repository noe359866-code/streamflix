package com.streamflixreborn.streamflix.search

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.Presenter
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.profiles.KidsContentFilter
import com.streamflixreborn.streamflix.profiles.TvProfileRepository
import com.streamflixreborn.streamflix.stremio.StremioAddonsClient
import com.streamflixreborn.streamflix.stremio.StremioMeta
import com.streamflixreborn.streamflix.tv.model.TvMediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native Leanback `SearchSupportFragment` integrating Android TV remote microphone speech search
 * with live Spanish catalog results (`Nuvio` + `Cinemeta`).
 */
class TvLeanbackSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private var activeSearchJob: Job? = null

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                val cleaned = TvVoiceQueryNormalizer.normalizeSpokenQuery(spoken)
                setSearchQuery(cleaned, true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)

        setOnItemViewClickedListener { _, item, _, _ ->
            val card = item as? LeanbackSearchResultItem ?: return@setOnItemViewClickedListener
            val deepLinkUri = TvVoiceQueryNormalizer.buildDeepLinkUri(
                kind = card.kind,
                contentId = card.contentId,
                imdbId = card.imdbId,
                title = card.title,
            )
            val launchIntent = Intent(requireContext(), MainTvActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = deepLinkUri
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launchIntent)
            requireActivity().finish()
        }
    }

    fun triggerVoicePromptIfAvailable() {
        runCatching {
            val intent = recognizerIntent.apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-419")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Di el nombre de una película o serie…")
            }
            speechRecognizerLauncher.launch(intent)
        }
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String?): Boolean {
        scheduleSearch(newQuery.orEmpty(), debounceMs = 260L)
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        scheduleSearch(query.orEmpty(), debounceMs = 0L)
        return true
    }

    private fun scheduleSearch(rawQuery: String, debounceMs: Long) {
        activeSearchJob?.cancel()
        val normalized = TvVoiceQueryNormalizer.normalizeSpokenQuery(rawQuery)
        if (normalized.isBlank()) {
            rowsAdapter.clear()
            return
        }

        activeSearchJob = lifecycleScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            val isKids = TvProfileRepository.isLastActiveProfileKids(requireContext())

            val (movies, series) = withContext(Dispatchers.IO) {
                coroutineScope {
                    val nm = async { StremioAddonsClient.searchNuvio("movie", normalized) }
                    val ns = async { StremioAddonsClient.searchNuvio("series", normalized) }
                    val cm = async { StremioAddonsClient.searchCinemeta("movie", normalized) }
                    val cs = async { StremioAddonsClient.searchCinemeta("series", normalized) }

                    val movieCards = mergeItems(nm.await(), cm.await(), TvMediaKind.MOVIE, isKids)
                    val seriesCards = mergeItems(ns.await(), cs.await(), TvMediaKind.SERIES, isKids)
                    movieCards to seriesCards
                }
            }

            rowsAdapter.clear()
            if (movies.isNotEmpty()) {
                val movieAdapter = ArrayObjectAdapter(LeanbackPosterCardPresenter())
                movies.forEach { movieAdapter.add(it) }
                rowsAdapter.add(ListRow(HeaderItem(0L, "Películas"), movieAdapter))
            }
            if (series.isNotEmpty()) {
                val seriesAdapter = ArrayObjectAdapter(LeanbackPosterCardPresenter())
                series.forEach { seriesAdapter.add(it) }
                rowsAdapter.add(ListRow(HeaderItem(1L, "Series y Doramas"), seriesAdapter))
            }
        }
    }

    private fun mergeItems(
        nuvio: List<StremioMeta>,
        cinemeta: List<StremioMeta>,
        kind: TvMediaKind,
        isKids: Boolean,
    ): List<LeanbackSearchResultItem> {
        val seen = HashSet<String>()
        val out = ArrayList<LeanbackSearchResultItem>()
        for (meta in nuvio + cinemeta) {
            val primaryId = meta.resolvedImdbId ?: meta.id
            if (primaryId.isBlank() || meta.name.isBlank()) continue
            val poster = meta.poster ?: meta.background ?: continue
            if (isKids && !KidsContentFilter.isMetaAllowedForKids(meta)) continue
            if (!seen.add(primaryId)) continue

            StremioAddonsClient.recordHint(primaryId, meta, null)
            out.add(
                LeanbackSearchResultItem(
                    contentId = primaryId,
                    imdbId = meta.resolvedImdbId,
                    kind = kind,
                    title = meta.name,
                    subtitle = listOfNotNull(
                        if (kind == TvMediaKind.SERIES) "Serie" else "Película",
                        meta.resolvedYear,
                    ).joinToString(" · "),
                    posterUrl = poster,
                )
            )
        }
        return out.take(20)
    }

    private data class LeanbackSearchResultItem(
        val contentId: String,
        val imdbId: String?,
        val kind: TvMediaKind,
        val title: String,
        val subtitle: String,
        val posterUrl: String,
    )

    private class LeanbackPosterCardPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val cardView = ImageCardView(parent.context).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setMainImageDimensions(210, 315)
                setInfoAreaBackgroundColor(Color.parseColor("#141722"))
            }
            return ViewHolder(cardView)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val data = item as? LeanbackSearchResultItem ?: return
            val cardView = viewHolder.view as ImageCardView
            cardView.titleText = data.title
            cardView.contentText = data.subtitle
            cardView.mainImageView?.let { imageView ->
                Glide.with(cardView.context)
                    .load(data.posterUrl)
                    .centerCrop()
                    .placeholder(ContextCompat.getDrawable(cardView.context, R.drawable.glide_fallback_cover))
                    .into(imageView)
            }
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            val cardView = viewHolder.view as ImageCardView
            cardView.badgeImage = null
            cardView.mainImage = null
        }
    }
}
