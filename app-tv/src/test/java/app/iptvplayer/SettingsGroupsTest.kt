package app.iptvplayer

import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.Settings
import app.iptvplayer.ui.SettingsGroup
import app.iptvplayer.ui.settingGroupCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsGroupsTest {
    @Test
    fun groupsKeepAStableOrderAndCountConditionalRows() {
        val regular = settingGroupCounts(Settings(preview = false)).toMap()
        assertEquals(SettingsGroup.entries.toList(), settingGroupCounts(Settings(preview = false)).map { it.first })
        assertEquals(3, regular[SettingsGroup.PLAYLIST])
        assertEquals(3, regular[SettingsGroup.LIVE])

        val conditional = settingGroupCounts(Settings(playlistRefresh = PlaylistRefresh.EVERY_N_DAYS, preview = true)).toMap()
        assertEquals(4, conditional[SettingsGroup.PLAYLIST])
        assertEquals(4, conditional[SettingsGroup.LIVE])
    }
}
