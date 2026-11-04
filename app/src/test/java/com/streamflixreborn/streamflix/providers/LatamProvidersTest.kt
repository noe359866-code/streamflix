package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los providers en español/LatAm que se portaron deben existir en el registro y
 * ser coherentes con su catálogo (series-only vs películas).
 */
class LatamProvidersTest {

    private val registered = Provider.providers.keys.map { it.name }

    @Test
    fun `los providers de LatAm portados estan registrados`() {
        listOf(
            "Cuevana 3",
            "SoloLatino",
            "Doramasflix",
            "Series Turcas",
            "CineHax",
            "Pluto TV MX",
            "Pluto TV AR",
            "MAGISTV",
        ).forEach { name ->
            assertTrue("falta $name en Provider.providers", registered.contains(name))
        }
    }

    @Test
    fun `los providers de solo series no fingen tener peliculas`() {
        listOf(SeriesTurcasProvider, MagisTvProvider, PlutoTvArProvider).forEach { provider ->
            assertFalse(provider.name, Provider.supportsMovies(provider))
            assertTrue(provider.name, Provider.supportsTvShows(provider))
        }
    }

    @Test
    fun `los proveedores de peliculas siguen soportando ambos tipos`() {
        listOf(DoramasflixProvider, CineHaxProvider, CuevanaEuProvider, SoloLatinoProvider)
            .forEach { provider ->
                assertTrue(provider.name, Provider.supportsMovies(provider))
                assertTrue(provider.name, Provider.supportsTvShows(provider))
            }
    }

    @Test
    fun `todos los providers hablan español o latin`() {
        val latam = listOf(
            DoramasflixProvider,
            SeriesTurcasProvider,
            CineHaxProvider,
            PlutoTvArProvider,
            MagisTvProvider,
            CuevanaEuProvider,
            SoloLatinoProvider,
        )
        latam.forEach { assertEquals(it.name, "es", it.language) }
    }

    @Test
    fun `el registro no tiene proveedores duplicados`() {
        assertEquals(registered.size, registered.distinct().size)
    }
}
