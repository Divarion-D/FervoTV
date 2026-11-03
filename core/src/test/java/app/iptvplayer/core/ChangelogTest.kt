package app.iptvplayer.core

import android.content.Context
import androidx.room.Room
import app.iptvplayer.core.db.AppDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChangelogTest {
    private val context = RuntimeEnvironment.getApplication()
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private lateinit var db: AppDb

    @Before fun setup() {
        preferences.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    @Test fun freshInstallRecordsBaselineWithoutOfferingChanges() {
        assertFalse(Repository(context, db).offerChangelog("0.1.2", updated = false))
        assertEquals("0.1.2", preferences.getString("seenVersion", null))
        assertFalse(Repository(context, db).offerChangelog("0.1.2", updated = true))
    }

    @Test fun checkingTheOfferDoesNotAcknowledgeIt() {
        preferences.edit().putString("seenVersion", "0.1.1").commit()
        val repo = Repository(context, db)
        assertTrue(repo.offerChangelog("0.1.2", updated = true))
        assertEquals("0.1.1", preferences.getString("seenVersion", null))
        assertTrue(repo.offerChangelog("0.1.2", updated = true))
    }

    @Test fun pendingOfferSurvivesRepositoryRecreation() {
        preferences.edit().putString("seenVersion", "0.1.1").commit()
        assertTrue(Repository(context, db).offerChangelog("0.1.2", updated = true))
        assertTrue(Repository(context, db).offerChangelog("0.1.2", updated = true))
    }

    @Test fun legacyUpdateWithoutMarkerIsNotConsumedByChecking() {
        assertTrue(Repository(context, db).offerChangelog("0.1.2", updated = true))
        assertTrue(Repository(context, db).offerChangelog("0.1.2", updated = true))
    }

}
