package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.scrapers.M3UProvider

/**
 * MAGISTV (antes "CineCity"): lista IPTV en español, canales en vivo de LatAm.
 *
 * Usa la lista pública del repo `CINECITY2023/cinecity` sobre [M3UProvider], así
 * que hereda el filtro de "sólo streams": nada de entradas de soporte,
 * donaciones ni canales con URL muerta.
 */
object MagisTvProvider : M3UProvider() {

    override val name = "MAGISTV"
    override val baseUrl = "https://raw.githubusercontent.com"
    override val logo = "https://i.ibb.co/39Ld2wbt/MAGISTV.png"
    override val language = "es"

    override val playlistUrl =
        "https://raw.githubusercontent.com/CINECITY2023/cinecity/cinecity.net/principal.m3u"

    override val ungroupedCategoryName = "General / Sin Categoría"
    override val homeLimit = 30
}
