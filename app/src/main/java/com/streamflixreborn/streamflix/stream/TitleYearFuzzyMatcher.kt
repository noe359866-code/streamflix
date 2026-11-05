package com.streamflixreborn.streamflix.stream

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Normalizador de Nombre y Año con Fuzzy Matching (Levenshtein + Jaccard de tokens + Año)
 * para validar resultados de Query Scraping en sitios que no soportan IDs de IMDb/TMDB.
 */
object TitleYearFuzzyMatcher {

    private val YEAR_IN_PARENS_REGEX = Regex("""[\(\[\-]\s*(19\d{2}|20\d{2})\s*[\)\]]?""")
    private val NON_ALPHANUM_REGEX = Regex("""[^a-z0-9\s]""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")
    private val COMBINING_MARKS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")

    private val NOISE_SUFFIX_TOKENS = setOf(
        "latino", "castellano", "subtitulado", "espanol", "online", "hd", "1080p", "720p"
    )

    fun normalizeTitle(raw: String): String {
        if (raw.isBlank()) return ""
        val withoutYear = raw.replace(YEAR_IN_PARENS_REGEX, " ")
        val decomposed = Normalizer.normalize(withoutYear, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
        val tokens = decomposed
            .lowercase(Locale.ROOT)
            .replace(NON_ALPHANUM_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
            .split(" ")
            .filter { it.isNotEmpty() && it !in NOISE_SUFFIX_TOKENS }
        return tokens.joinToString(" ")
    }

    fun extractYear(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Computes a 0..115 fuzzy match score between a scraped title/year and expected title(s)/year.
     * Candidates scoring >= 58 are accepted as valid title matches.
     */
    fun scoreMatch(
        candidateTitle: String,
        targetTitles: List<String>,
        candidateYearRaw: String?,
        expectedYearRaw: String?,
    ): Int {
        val normCandidate = normalizeTitle(candidateTitle)
        if (normCandidate.isEmpty()) return 0

        val candYear = extractYear(candidateYearRaw) ?: extractYear(candidateTitle)
        val expYear = extractYear(expectedYearRaw)

        var bestScore = 0
        for (target in targetTitles) {
            val normTarget = normalizeTitle(target)
            if (normTarget.isEmpty()) continue

            val levSim = levenshteinSimilarity(normCandidate, normTarget) // 0.0 .. 1.0
            val tokenSim = tokenJaccardSimilarity(normCandidate, normTarget) // 0.0 .. 1.0

            var score = when {
                normCandidate == normTarget -> 100
                normCandidate.startsWith("$normTarget ") || normTarget.startsWith("$normCandidate ") ->
                    max(84, (levSim * 92.0).toInt())
                normCandidate.contains(normTarget) || normTarget.contains(normCandidate) -> {
                    val lenRatio = min(normCandidate.length, normTarget.length).toDouble() /
                        max(normCandidate.length, normTarget.length).toDouble()
                    (62.0 + lenRatio * 28.0).toInt()
                }
                else -> {
                    val blended = (levSim * 0.55) + (tokenSim * 0.45)
                    (blended * 92.0).toInt()
                }
            }

            // Guard sequel numbers ("2", "3", "ii", "iii") from matching wrong installment
            val targetDigits = normTarget.split(" ").filter { it.all(Char::isDigit) }.toSet()
            val candDigits = normCandidate.split(" ").filter { it.all(Char::isDigit) }.toSet()
            if (targetDigits != candDigits && (targetDigits.isNotEmpty() || candDigits.isNotEmpty())) {
                score -= 18
            }

            if (expYear != null && candYear != null) {
                val diff = abs(expYear - candYear)
                score += when {
                    diff == 0 -> 14
                    diff == 1 -> 6
                    diff == 2 -> -6
                    else -> -26
                }
            }

            if (score > bestScore) bestScore = score
        }
        return bestScore
    }

    private fun levenshteinSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost,
                )
            }
            for (j in 0..b.length) prev[j] = curr[j]
        }

        val dist = prev[b.length]
        return (1.0 - (dist.toDouble() / maxLen.toDouble())).coerceIn(0.0, 1.0)
    }

    private fun tokenJaccardSimilarity(a: String, b: String): Double {
        val tokensA = a.split(" ").filter { it.isNotEmpty() }.toSet()
        val tokensB = b.split(" ").filter { it.isNotEmpty() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val inter = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return inter.toDouble() / union.toDouble()
    }
}
