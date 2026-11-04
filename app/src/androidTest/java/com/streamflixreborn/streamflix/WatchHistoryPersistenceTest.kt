package com.streamflixreborn.streamflix

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.WatchItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real SQLite round-trips, including closing and reopening the database file. */
@RunWith(AndroidJUnit4::class)
class WatchHistoryPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "streamflix-test-${UUID.randomUUID()}.db"
    private lateinit var database: AppDatabase
    private val history = WatchItem.WatchHistory(1_700_000_000_000L, 60_000L, 120_000L)

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .allowMainThreadQueries().build()

    @Before fun setUp() { database = open() }
    @After fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun movieProgressSurvivesDatabaseReopen() {
        database.movieDao().insert(Movie(id = "movie-1", title = "Película").apply { watchHistory = history })
        database.close()
        database = open()
        assertEquals(history, database.movieDao().getById("movie-1")?.watchHistory)
    }

    @Test fun catalogRefreshPreservesProgressAndFavorite() {
        val dao = database.movieDao()
        dao.insert(Movie(id = "movie-2", title = "Película").apply {
            watchHistory = history
            isFavorite = true
            favoritedAtMillis = 123L
        })
        dao.save(Movie(id = "movie-2", title = "Título actualizado"))
        val saved = checkNotNull(dao.getById("movie-2"))
        assertEquals("Título actualizado", saved.title)
        assertEquals(history, saved.watchHistory)
        assertTrue(saved.isFavorite)
        assertEquals(123L, saved.favoritedAtMillis)
    }

    @Test fun episodeProgressSurvivesDatabaseReopen() {
        database.episodeDao().insert(Episode(id = "episode-1", number = 1).apply { watchHistory = history })
        database.close()
        database = open()
        assertEquals(history, database.episodeDao().getById("episode-1")?.watchHistory)
    }

    @Test fun episodeMergePreservesHistoryWhenCatalogOmitsIt() {
        val dao = database.episodeDao()
        dao.insert(Episode(id = "episode-2", number = 2).apply { watchHistory = history })
        dao.save(Episode(id = "episode-2", number = 2, title = "Siguiente"))
        assertEquals(history, dao.getById("episode-2")?.watchHistory)
        assertEquals("Siguiente", dao.getById("episode-2")?.title)
    }
}
