package com.streamflixreborn.streamflix.profiles

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TvProfileDao {

    @Query("SELECT * FROM tv_user_profiles ORDER BY createdAtMs ASC")
    fun observeProfiles(): Flow<List<TvUserProfileEntity>>

    @Query("SELECT * FROM tv_user_profiles ORDER BY createdAtMs ASC")
    suspend fun getProfiles(): List<TvUserProfileEntity>

    @Query("SELECT COUNT(*) FROM tv_user_profiles")
    suspend fun getProfileCount(): Int

    @Query("SELECT * FROM tv_user_profiles WHERE id = :profileId LIMIT 1")
    suspend fun getProfileById(profileId: Long): TvUserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: TvUserProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: TvUserProfileEntity)

    @Query("DELETE FROM tv_user_profiles WHERE id = :profileId")
    suspend fun deleteProfile(profileId: Long)

    @Query("DELETE FROM tv_profile_watch_history WHERE profileId = :profileId")
    suspend fun clearHistoryForProfile(profileId: Long)

    @Query("DELETE FROM tv_profile_favorites WHERE profileId = :profileId")
    suspend fun clearFavoritesForProfile(profileId: Long)

    @Query(
        """
        SELECT * FROM tv_profile_watch_history
        WHERE profileId = :profileId
        ORDER BY updatedAtMs DESC
        LIMIT 25
        """
    )
    fun observeContinueWatching(profileId: Long): Flow<List<TvProfileWatchHistoryEntity>>

    @Query(
        """
        SELECT * FROM tv_profile_watch_history
        WHERE profileId = :profileId AND mediaKey = :mediaKey
        LIMIT 1
        """
    )
    suspend fun getWatchEntry(profileId: Long, mediaKey: String): TvProfileWatchHistoryEntity?

    @Query(
        """
        SELECT * FROM tv_profile_watch_history
        WHERE profileId = :profileId AND contentId = :contentId
        ORDER BY updatedAtMs DESC
        LIMIT 1
        """
    )
    suspend fun getLatestWatchEntryForContent(
        profileId: Long,
        contentId: String,
    ): TvProfileWatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchEntry(entry: TvProfileWatchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchEntries(entries: List<TvProfileWatchHistoryEntity>)

    @Query("DELETE FROM tv_profile_watch_history WHERE profileId = :profileId AND mediaKey = :mediaKey")
    suspend fun deleteWatchEntry(profileId: Long, mediaKey: String)

    // Profile Favorites & Trakt Cloud Watchlist
    @Query(
        """
        SELECT * FROM tv_profile_favorites
        WHERE profileId = :profileId
        ORDER BY addedAtMs DESC
        LIMIT 40
        """
    )
    fun observeFavorites(profileId: Long): Flow<List<TvProfileFavoriteEntity>>

    @Query("SELECT * FROM tv_profile_favorites WHERE profileId = :profileId ORDER BY addedAtMs DESC")
    suspend fun getFavorites(profileId: Long): List<TvProfileFavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorites(favorites: List<TvProfileFavoriteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(favorite: TvProfileFavoriteEntity)

    @Query("DELETE FROM tv_profile_favorites WHERE profileId = :profileId AND contentId = :contentId")
    suspend fun deleteFavorite(profileId: Long, contentId: String)

    // Custom Stremio Add-ons installed via Local QR Pairing or Manual URL
    @Query("SELECT * FROM tv_custom_addons ORDER BY installedAtMs DESC")
    fun observeCustomAddons(): Flow<List<TvCustomAddonEntity>>

    @Query("SELECT * FROM tv_custom_addons WHERE isEnabled = 1 ORDER BY installedAtMs DESC")
    suspend fun getEnabledCustomAddons(): List<TvCustomAddonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomAddon(addon: TvCustomAddonEntity)

    @Query("UPDATE tv_custom_addons SET isEnabled = :enabled WHERE addonId = :addonId")
    suspend fun setCustomAddonEnabled(addonId: String, enabled: Boolean)

    @Query("DELETE FROM tv_custom_addons WHERE addonId = :addonId")
    suspend fun deleteCustomAddon(addonId: String)
}
