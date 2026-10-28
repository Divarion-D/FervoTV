package app.iptvplayer.core.player

import android.view.SurfaceView
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Test engine: records calls, state is set by the test. */
class FakeEngine : PlaybackEngine {
    val calls = mutableListOf<String>()
    override val state = MutableStateFlow(PlaybackState())
    override fun play(item: PlayItem) { calls += "play ${item.url}"; state.value = state.value.copy(paused = false) }
    override fun retry() { calls += "retry" }
    override fun pause() { calls += "pause"; state.value = state.value.copy(paused = true) }
    override fun resume() { calls += "resume"; state.value = state.value.copy(paused = false) }
    override fun stop() { calls += "stop" }
    override fun setVideoView(view: SurfaceView?) { calls += "view" }
    override fun release() { calls += "release" }
}

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
}
