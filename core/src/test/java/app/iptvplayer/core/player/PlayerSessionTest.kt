package app.iptvplayer.core.player

import android.view.SurfaceView
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Test engine: records calls, state is set by the test. */
class FakeEngine : PlaybackEngine {
    val calls = mutableListOf<String>()
    override val state = MutableStateFlow(PlaybackState())
    override fun play(item: PlayItem) { calls += "play ${item.url}" + if (item.start > 0) " @${item.start}" else ""; state.value = state.value.copy(paused = false) }
    override fun retry() { calls += "retry" }
    override fun pause() { calls += "pause"; state.value = state.value.copy(paused = true) }
    override fun resume() { calls += "resume"; state.value = state.value.copy(paused = false) }
    override fun stop() { calls += "stop" }
    override fun setMuted(muted: Boolean) { calls += "muted $muted" }
    override var position = 0L
    override var duration = 0L
    override fun seekTo(ms: Long) { calls += "seek $ms"; position = ms }
    override fun setVideoView(view: SurfaceView?) { calls += if (view == null) "view null" else "view" }
    override fun release() { calls += "release" }
}

@RunWith(RobolectricTestRunner::class) // a real SurfaceView for the view swap
class PlayerSessionTest {
    private val engine = FakeEngine()
    private val session = PlayerSession { engine }

    @Test
    fun liveIsReleasedInBackgroundAndStartsAgainInFront() {
        session.open(PlayItem("http://a/1.ts"))
        session.background()
        session.foreground()
        assertEquals(listOf("play http://a/1.ts", "stop", "play http://a/1.ts"), engine.calls)
    }

    @Test
    fun nonLivePausesAndContinues() {
        session.open(PlayItem("http://a/film.mp4", live = false))
        session.background()
        session.foreground()
        assertEquals(listOf("play http://a/film.mp4", "pause", "resume"), engine.calls)
    }

    @Test
    fun foregroundWithoutBackgroundJustContinues() {
        session.open(PlayItem("http://a/1.ts"))
        session.foreground() // the screen's first ON_START
        assertEquals(listOf("play http://a/1.ts", "resume"), engine.calls)
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
}
