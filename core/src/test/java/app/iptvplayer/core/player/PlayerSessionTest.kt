package app.iptvplayer.core.player

import android.view.SurfaceView
import android.view.View
import app.iptvplayer.core.model.Source
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import android.os.Looper
import java.time.Duration

/** Test engine: records calls, state is set by the test. */
class FakeEngine : PlaybackEngine {
    val calls = mutableListOf<String>()
    var item: PlayItem? = null
    override val state = MutableStateFlow(PlaybackState())
    override fun play(item: PlayItem) { this.item = item; calls += "play ${item.url}" + if (item.start > 0) " @${item.start}" else ""; state.value = state.value.copy(paused = false) }
    override fun retry() { calls += "retry" }
    override fun pause() { calls += "pause"; state.value = state.value.copy(paused = true) }
    override fun resume() { calls += "resume"; state.value = state.value.copy(paused = false) }
    override fun stop() { calls += "stop" }
    override fun setMuted(muted: Boolean) { calls += "muted $muted" }
    override fun setVolume(percent: Int) { calls += "volume $percent" }
    override fun selectAudioTrack(index: Int) { calls += "audio $index" }
    override fun selectSubtitleTrack(index: Int) { calls += "subtitle $index" }
    override fun disableSubtitles() { calls += "subtitles off" }
    override fun setAudioOffset(ms: Long) { calls += "delay $ms" }
    override fun selectVideoTrack(index: Int) { calls += "video $index" }
    override fun clearVideoTrackOverride() { calls += "video auto" }
    override fun setSpeed(speed: Float) { calls += "speed $speed" }
    override fun setScale(scale: VideoScale) { calls += "scale $scale" }
    override var position = 0L
    override var duration = 0L
    override fun seekTo(ms: Long) { calls += "seek $ms"; position = ms }
    override fun setVideoView(view: View?) { calls += if (view == null) "view null" else "view" }
    override fun setSubtitleView(view: View?) { calls += if (view == null) "subtitles view null" else "subtitles view" }
    override fun release() { calls += "release" }
    var previousHandler: (() -> Unit)? = null
    var nextHandler: (() -> Unit)? = null
    override fun setMediaNavigationHandlers(previous: (() -> Unit)?, next: (() -> Unit)?) {
        previousHandler = previous
        nextHandler = next
    }
}

@RunWith(RobolectricTestRunner::class) // a real SurfaceView for the view swap
class PlayerSessionTest {
    private val engine = FakeEngine()
    private val session = PlayerSession { engine }

    @Test fun currentItemIsObservableForTheAppLevelPlayer() {
        val item = PlayItem("http://a/live", title = "News")
        session.open(item)
        assertEquals("News", session.currentItem.value?.title)
        session.close()
        assertEquals(null, session.currentItem.value)
    }

    @Test fun reconfigureKeepsVodPositionPauseAndSessionControls() {
        val first = FakeEngine()
        val second = FakeEngine()
        val player = PlayerSession { first }
        player.open(PlayItem("http://a/film.mkv", live = false))
        first.position = 42_000
        player.setMuted(true)
        player.setSpeed(1.25f)
        player.setPaused(true)

        player.reconfigure { second }

        assertEquals(listOf("play http://a/film.mkv", "muted true", "speed 1.25", "pause", "release"), first.calls)
        assertEquals("http://a/film.mkv", player.current?.url)
        assertEquals(42_000L, player.current?.start)
        assertEquals(
            listOf("play http://a/film.mkv @42000", "muted true", "speed 1.25", "pause"),
            second.calls.filterNot { it == "view null" },
        )
    }

    @Test fun sourcePlaybackHeadersIgnoreBlanks() {
        assertEquals(
            mapOf("User-Agent" to "TV", "Referer" to "https://portal/"),
            Source.M3u("http://list", "TV", "https://portal/").playbackHeaders(),
        )
        assertEquals(emptyMap<String, String>(), Source.Xtream("http://h", "u", "p", userAgent = " ").playbackHeaders())
    }

    @Test fun trackChoicesAndAudioDelayAreRememberedPerItem() {
        val memory = PlayerMemory(RuntimeEnvironment.getApplication())
        listOf(PlayerMemory.Kind.AUDIO_TRACK, PlayerMemory.Kind.SUBTITLE_TRACK, PlayerMemory.Kind.AUDIO_DELAY).forEach(memory::reset)
        val remembered = PlayerSession(memory) { engine }
        remembered.open(PlayItem("http://a", key = "channel-7"))
        remembered.selectAudioTrack(2)
        remembered.disableSubtitles()
        remembered.setAudioOffset(75)

        assertEquals("2", memory.get("channel-7", true, PlayerMemory.Kind.AUDIO_TRACK))
        assertEquals("-1", memory.get("channel-7", true, PlayerMemory.Kind.SUBTITLE_TRACK))
        assertEquals("75", memory.get("channel-7", true, PlayerMemory.Kind.AUDIO_DELAY))

        val secondEngine = FakeEngine()
        PlayerSession(memory) { secondEngine }.open(PlayItem("http://new-url", key = "channel-7"))
        assertEquals(2, secondEngine.item?.rememberedAudioTrack)
        assertEquals(-1, secondEngine.item?.rememberedSubtitleTrack)
        assertEquals(listOf("play http://new-url", "delay 75"), secondEngine.calls)
    }

    @Test fun currentVideoQualityDelegatesWithoutBeingRemembered() {
        session.open(PlayItem("http://adaptive.m3u8"))
        session.selectVideoTrack(3)
        session.clearVideoTrackOverride()
        assertEquals(listOf("play http://adaptive.m3u8", "video 3", "video auto"), engine.calls)
    }

    @Test fun workingFormatIsRestoredForLiveButNotVod() {
        val memory = PlayerMemory(RuntimeEnvironment.getApplication())
        memory.reset(PlayerMemory.Kind.FORMAT)
        memory.put("same-item", live = true, PlayerMemory.Kind.FORMAT, "application/x-mpegURL")

        val liveEngine = FakeEngine()
        PlayerSession(memory) { liveEngine }.open(PlayItem("http://a/live", key = "same-item"))
        assertEquals("application/x-mpegURL", liveEngine.item?.rememberedFormat)

        val vodEngine = FakeEngine()
        PlayerSession(memory) { vodEngine }.open(PlayItem("http://a/movie", key = "same-item", live = false))
        assertEquals(null, vodEngine.item?.rememberedFormat)
    }

    @Test fun mediaPreviousAndNextHandlersReachTheEngine() {
        var previous = 0
        var next = 0
        session.setMediaNavigationHandlers({ previous++ }, { next++ })
        engine.previousHandler?.invoke()
        engine.nextHandler?.invoke()
        session.setMediaNavigationHandlers(null, null)
        assertEquals(1, previous)
        assertEquals(1, next)
        assertEquals(null, engine.previousHandler)
        assertEquals(null, engine.nextHandler)
    }

    @Test fun rememberedScaleAndVolumeAreAppliedAndUpdatedForTheCurrentItem() {
        val memory = PlayerMemory(RuntimeEnvironment.getApplication())
        memory.reset(PlayerMemory.Kind.SCALE)
        memory.reset(PlayerMemory.Kind.VOLUME)
        memory.put("movie-42", live = false, PlayerMemory.Kind.SCALE, VideoScale.FILL.name)
        memory.put("movie-42", live = false, PlayerMemory.Kind.VOLUME, "125")
        val remembered = PlayerSession(memory) { engine }

        remembered.open(PlayItem("http://a/film.mp4", key = "movie-42", live = false))
        remembered.setScale(VideoScale.RATIO_4_3)
        remembered.setVolume(75)

        assertEquals(
            listOf("play http://a/film.mp4", "scale FILL", "volume 125", "scale RATIO_4_3", "volume 75"),
            engine.calls,
        )
        assertEquals(VideoScale.RATIO_4_3.name, memory.get("movie-42", live = false, PlayerMemory.Kind.SCALE))
        assertEquals("75", memory.get("movie-42", live = false, PlayerMemory.Kind.VOLUME))
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun sleepTimerStopsAtTheDeadlineEvenBetweenTicks() {
        session.open(PlayItem("http://a/film.mp4", live = false))
        session.setSleepTimer(1_500)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_499))
        assertEquals("http://a/film.mp4", session.current?.url)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(null, session.current)
        assertEquals(null, session.sleepRemainingMs.value)
        assertEquals(listOf("play http://a/film.mp4", "stop"), engine.calls)
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun sleepTimerCanBeReplacedAndCancelledWithoutStoppingPlayback() {
        session.open(PlayItem("http://a/1.ts"))
        session.setSleepTimer(1_000)
        session.setSleepTimer(3_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2_000L, session.sleepRemainingMs.value)
        session.setSleepTimer(null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals("http://a/1.ts", session.current?.url)
        assertEquals(null, session.sleepRemainingMs.value)
        assertEquals(listOf("play http://a/1.ts"), engine.calls)
    }

    @Test fun sleepAtEndIsConsumedOnceAndSuppressesAutoNext() {
        session.open(PlayItem("http://a/episode.mp4", live = false))
        session.setSleepAtEnd(true)
        assertEquals(true, session.sleepAtEnd.value)
        assertEquals(true, session.consumeSleepAtEnd())
        assertEquals(false, session.consumeSleepAtEnd())
        assertEquals(false, session.sleepAtEnd.value)
        assertEquals(listOf("play http://a/episode.mp4"), engine.calls)
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun closingTheSessionRemovesTheOldTimerBeforeAnotherChannel() {
        session.open(PlayItem("http://a/1.ts"))
        session.setSleepTimer(1_000)
        session.close()
        session.open(PlayItem("http://a/2.ts"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals("http://a/2.ts", session.current?.url)
        assertEquals(null, session.sleepRemainingMs.value)
        assertEquals(listOf("play http://a/1.ts", "stop", "play http://a/2.ts"), engine.calls)
    }

    @Test
    fun liveIsReleasedInBackgroundAndStartsAgainInFront() {
        session.open(PlayItem("http://a/1.ts"))
        session.background()
        session.foreground()
        assertEquals(listOf("play http://a/1.ts", "stop", "play http://a/1.ts"), engine.calls)
    }

    @Test
    fun repeatedLiveBackgroundForegroundKeepsTheSameStream() {
        session.open(PlayItem("http://a/1.ts"))
        repeat(2) {
            session.background()
            session.foreground()
        }

        assertEquals(
            listOf("play http://a/1.ts", "stop", "play http://a/1.ts", "stop", "play http://a/1.ts"),
            engine.calls,
        )
        assertEquals("http://a/1.ts", session.current?.url)
    }

    @Test
    fun nonLivePausesAndContinues() {
        session.open(PlayItem("http://a/film.mp4", live = false))
        session.background()
        session.foreground()
        assertEquals(listOf("play http://a/film.mp4", "pause", "resume"), engine.calls)
    }

    @Test
    fun explicitVodPauseRemainsPausedThroughBackgroundForegroundSequence() {
        session.open(PlayItem("http://a/film.mp4", live = false))
        session.setPaused(true)
        session.background()
        session.foreground()

        assertEquals(listOf("play http://a/film.mp4", "pause", "pause"), engine.calls)
        assertEquals("http://a/film.mp4", session.current?.url)
    }

    @Test
    fun closingWhileBackgroundedPreventsAStaleForegroundResume() {
        session.open(PlayItem("http://a/1.ts"))
        session.background()
        session.close()
        session.foreground()

        assertEquals(listOf("play http://a/1.ts", "stop", "stop"), engine.calls)
        assertEquals(null, session.current)
    }

    @Test
    fun foregroundWithoutBackgroundIsANoOp() {
        session.open(PlayItem("http://a/1.ts"))
        session.foreground() // the screen's first ON_START
        assertEquals(listOf("play http://a/1.ts"), engine.calls)
    }

    @Test
    fun explicitPauseAndPlayNeverToggleOrReopenTheItem() {
        session.setPaused(true)
        session.setPaused(false)
        assertEquals(emptyList<String>(), engine.calls)
        session.open(PlayItem("http://a/film.mp4", live = false))
        engine.position = 12_000L
        repeat(2) { session.setPaused(false) }
        assertEquals(false, engine.state.value.paused)
        repeat(2) { session.setPaused(true) }
        assertEquals(true, engine.state.value.paused)
        assertEquals(12_000L, session.position)
        assertEquals(1, engine.calls.count { it.startsWith("play ") })
    }

    @Test
    fun togglePauseRetryAndClose() {
        session.togglePause() // nothing open: ignored
        session.retry()
        session.open(PlayItem("http://a/1.ts"))
        session.togglePause()
        session.togglePause()
        session.retry()
        session.close()
        session.foreground() // closed: nothing to continue
        assertEquals(listOf("play http://a/1.ts", "pause", "resume", "retry", "stop"), engine.calls)
    }

    @Test
    fun engineIsCreatedOnFirstUseOnly() {
        var created = 0
        val lazy = PlayerSession { created++; FakeEngine() }
        lazy.close()
        lazy.release()
        assertEquals(0, created)
        lazy.open(PlayItem("http://a/1.ts"))
        lazy.open(PlayItem("http://a/2.ts"))
        assertEquals(1, created)
    }

    @Test
    fun sameItemGoesOnWithoutRestart() {
        session.open(PlayItem("http://a/1.ts")) // preview
        session.open(PlayItem("http://a/1.ts")) // full screen: the same stream
        engine.state.value = engine.state.value.copy(error = PlaybackError(PlaybackError.Kind.Network, "x"))
        session.open(PlayItem("http://a/1.ts")) // after an error it starts again
        session.open(PlayItem("http://a/2.ts"))
        assertEquals(listOf("play http://a/1.ts", "play http://a/1.ts", "play http://a/2.ts"), engine.calls)
        assertEquals("http://a/2.ts", session.current?.url)
    }

    @Test
    fun filmStartsAtItsPositionAndSeeks() {
        session.seekTo(5_000) // nothing open: ignored
        assertEquals(0, session.position)
        session.open(PlayItem("http://a/film.mp4", live = false, start = 754_000))
        session.seekTo(-3) // never before the start
        session.seekTo(764_000)
        assertEquals(764_000, session.position)
        session.close()
        assertEquals(0, session.position)
        assertEquals(listOf("play http://a/film.mp4 @754000", "seek 0", "seek 764000", "stop"), engine.calls)
    }

    @Test
    fun viewsSwapInAnyOrder() {
        val ctx = RuntimeEnvironment.getApplication()
        val preview = SurfaceView(ctx)
        val full = SurfaceView(ctx)
        session.setVideoView(preview)
        session.setVideoView(full) // full screen attached first...
        session.releaseVideoView(preview) // ...then the preview goes: the picture stays on full screen
        session.releaseVideoView(full)
        assertEquals(listOf("view", "view", "view null"), engine.calls)
    }

    @Test
    fun subtitleViewsSwapInAnyOrder() {
        val ctx = RuntimeEnvironment.getApplication()
        val preview = View(ctx)
        val full = View(ctx)
        session.setSubtitleView(preview)
        session.setSubtitleView(full)
        session.releaseSubtitleView(preview)
        session.releaseSubtitleView(full)
        assertEquals(listOf("subtitles view", "subtitles view", "subtitles view null"), engine.calls)
    }

    @Test
    fun channelHeadersTravelWithPlayItem() {
        val channel = app.iptvplayer.core.model.Channel(
            "Synthetic", "http://test/stream", "P3",
            userAgent = "FervoTV-Test", referer = "https://test.invalid/",
        )
        session.open(channel.playItem())
        assertEquals(
            mapOf("User-Agent" to "FervoTV-Test", "Referer" to "https://test.invalid/"),
            session.current?.headers,
        )
    }
}
