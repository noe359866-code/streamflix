package com.streamflixreborn.streamflix.stremio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class StremioAddonManifest(
    val id: String = "",
    val version: String = "",
    val name: String = "",
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val types: List<String> = emptyList(),
    val resources: List<JsonElement> = emptyList(),
    val catalogs: List<StremioManifestCatalog> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
) {
    val parsedResourceNames: List<String>
        get() = resources.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> (element["name"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()

    val resolvedIdPrefixes: List<String>
        get() {
            val resourceLevel = resources.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val arr = obj["idPrefixes"] as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }.flatten()
            return (idPrefixes + resourceLevel).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }

    fun isValidStremioManifest(): Boolean {
        if (id.isBlank() || name.isBlank() || version.isBlank()) return false
        val hasResourcesOrCatalogs = parsedResourceNames.isNotEmpty() || catalogs.isNotEmpty()
        val hasSupportedTypes = types.isNotEmpty() || catalogs.any { it.type.isNotBlank() }
        return hasResourcesOrCatalogs && hasSupportedTypes
    }
}

@Serializable
data class StremioManifestCatalog(
    val type: String = "",
    val id: String = "",
    val name: String? = null,
)

data class ValidatedStremioAddon(
    val manifest: StremioAddonManifest,
    val manifestUrl: String,
    val baseUrl: String,
)
