package app.iptvplayer

import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.decodeGroup
import app.iptvplayer.core.home.ChannelContinue
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.ChannelItem
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.ui.JumpMode
import app.iptvplayer.ui.Section
import app.iptvplayer.ui.phoneSections
import app.iptvplayer.ui.playJump
import app.iptvplayer.ui.railSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionNavigationTest {
    @Test
    fun continueEncodesTheCategoryRatherThanInterpretingItsNameAsAList() {
        for (category in listOf("favorites", "c:News", "News:local")) {
            val channel = ChannelItem(Channel("Channel", "http://test/live", category), 1, false, false)
            val (section, jump) = ChannelContinue(channel, 100).playJump()
            assertEquals(Section.Live, section)
            assertEquals(JumpMode.PLAY, jump.mode)
            assertEquals(channel.channel.key, jump.key)
            assertEquals(ChannelGroup.Category(category), decodeGroup(jump.group!!))
        }
    }

    @Test
    fun navigationHidesUnavailableVodAndKeepsPhoneTabsWithinFive() {
        val liveOnly = railSections(emptySet())
        assertFalse(Section.Movies in liveOnly)
        assertFalse(Section.Series in liveOnly)
        assertTrue(Section.Search in liveOnly)
        assertTrue(Section.Guide in phoneSections(emptySet()))
        val both = phoneSections(VodKind.entries.toSet())
        assertEquals(5, both.size)
        assertEquals(listOf(Section.Home, Section.Live, Section.Movies, Section.Series, Section.More), both)
        assertFalse(Section.Search in both)
    }
}
