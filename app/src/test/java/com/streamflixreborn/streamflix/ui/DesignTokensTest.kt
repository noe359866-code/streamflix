package com.streamflixreborn.streamflix.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guardarraíles del sistema visual: la escala tipográfica debe estar declarada y
 * los layouts no deben volver a hardcodear tamaños/colores que rompen los temas.
 */
class DesignTokensTest {

    private val res = File("src/main/res")

    @Test
    fun `la escala tipografica apple-tv esta definida`() {
        val file = File(res, "values/text_appearances.xml")
        assertTrue("falta text_appearances.xml", file.exists())

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val styles = document.getElementsByTagName("style")
        val names = (0 until styles.length).map { styles.item(it).attributes.getNamedItem("name").nodeValue }

        listOf("Headline", "Title", "Subtitle", "Body", "Caption", "Badge", "Button").forEach { level ->
            assertTrue("falta TextAppearance.App.$level", names.contains("TextAppearance.App.$level"))
        }
    }

    @Test
    fun `los layouts de tarjeta no hardcodean texto blanco`() {
        val offenders = File(res, "layout").listFiles()
            .orEmpty()
            .filter { it.name.startsWith("item_") || it.name.startsWith("content_") }
            .filter { it.readText().contains("android:textColor=\"#FFFFFF\"") }
            .map { it.name }

        assertEquals("usar ?android:attr/textColorPrimary o Secondary: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `el radio de poster usa el token de dimensiones`() {
        val outline = File(res, "drawable/bg_poster_rounded.xml").readText()
        assertTrue(outline.contains("@dimen/poster_radius"))

        val dimens = File(res, "values/dimens.xml").readText()
        assertTrue(dimens.contains("name=\"poster_radius\""))
        assertTrue(dimens.contains("name=\"space_lg\""))
    }
}
