package com.streamflixreborn.streamflix.addons

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.profiles.TvCustomAddonEntity
import com.streamflixreborn.streamflix.profiles.TvProfileDatabase
import com.streamflixreborn.streamflix.stremio.StremioAddonsClient
import com.streamflixreborn.streamflix.stremio.StremioManifestCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

object CustomAddonRepository {

    private fun dao(context: Context = StreamFlixApp.instance) =
        TvProfileDatabase.getInstance(context).profileDao()

    fun observeCustomAddons(
        context: Context = StreamFlixApp.instance,
    ): Flow<List<TvCustomAddonEntity>> {
        return dao(context).observeCustomAddons()
    }

    suspend fun getEnabledCustomAddons(
        context: Context = StreamFlixApp.instance,
    ): List<TvCustomAddonEntity> = withContext(Dispatchers.IO) {
        dao(context).getEnabledCustomAddons()
    }

    suspend fun validateAndInstallAddon(
        rawManifestUrl: String,
        context: Context = StreamFlixApp.instance,
    ): Result<TvCustomAddonEntity> = withContext(Dispatchers.IO) {
        StremioAddonsClient.validateAndFetchManifest(rawManifestUrl).mapCatching { validated ->
            val manifest = validated.manifest
            val catalogsJson = runCatching {
                StremioAddonsClient.json.encodeToString(manifest.catalogs)
            }.getOrDefault("[]")

            val entity = TvCustomAddonEntity(
                addonId = manifest.id,
                name = manifest.name,
                version = manifest.version,
                description = manifest.description?.takeIf { it.isNotBlank() },
                logoUrl = manifest.logo?.takeIf { it.isNotBlank() },
                manifestUrl = validated.manifestUrl,
                baseUrl = validated.baseUrl,
                typesCsv = manifest.types.joinToString(","),
                resourcesCsv = manifest.parsedResourceNames.joinToString(","),
                catalogsJson = catalogsJson,
                idPrefixesCsv = manifest.resolvedIdPrefixes.joinToString(","),
                isEnabled = true,
                installedAtMs = System.currentTimeMillis(),
            )
            dao(context).upsertCustomAddon(entity)
            entity
        }
    }

    suspend fun setAddonEnabled(
        addonId: String,
        enabled: Boolean,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        dao(context).setCustomAddonEnabled(addonId, enabled)
    }

    suspend fun removeAddon(
        addonId: String,
        context: Context = StreamFlixApp.instance,
    ) = withContext(Dispatchers.IO) {
        dao(context).deleteCustomAddon(addonId)
    }

    fun decodeCatalogs(entity: TvCustomAddonEntity): List<StremioManifestCatalog> {
        if (entity.catalogsJson.isBlank()) return emptyList()
        return runCatching {
            StremioAddonsClient.json.decodeFromString<List<StremioManifestCatalog>>(entity.catalogsJson)
        }.getOrDefault(emptyList())
    }

    fun supportsResource(entity: TvCustomAddonEntity, resourceName: String): Boolean {
        return entity.resourcesCsv
            .split(",")
            .any { it.trim().equals(resourceName, ignoreCase = true) }
    }
}
