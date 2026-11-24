package app.deft.bridge

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserGestureTest {
    @Test
    fun oneActionPerRecentTap() {
        UserGesture.tapped("a", now = 1_000)
        assertTrue(UserGesture.consume("a", now = 2_000))
        assertFalse(UserGesture.consume("a", now = 2_100))
    }

    @Test
    fun staleTapDoesNotCount() {
        UserGesture.tapped("b", now = 1_000)
        assertFalse(UserGesture.consume("b", now = 1_000 + UserGesture.WINDOW_MS + 1))
    }

    @Test
    fun tapsAreScopedToTheApp() {
        UserGesture.tapped("c", now = 1_000)
        assertFalse(UserGesture.consume("d", now = 1_100))
        assertTrue(UserGesture.consume("c", now = 1_200))
    }

    private fun touch(id: String, action: Int, x: Float, y: Float, t: Long) =
        UserGesture.onTouch(id, action, x, y, t, slopPx = 16, maxPressMs = 400)

    @Test
    fun genuineTapCounts() {
        touch("tap", MotionEvent.ACTION_DOWN, 100f, 100f, 1_000)
        touch("tap", MotionEvent.ACTION_MOVE, 105f, 103f, 1_050)
        touch("tap", MotionEvent.ACTION_UP, 106f, 104f, 1_120)
        assertTrue(UserGesture.consume("tap", now = 1_500))
    }

    @Test
    fun endOfScrollIsNotATap() {
        touch("scroll", MotionEvent.ACTION_DOWN, 100f, 400f, 1_000)
        touch("scroll", MotionEvent.ACTION_MOVE, 100f, 300f, 1_080)
        // Finger comes back near the start before lifting: still a scroll.
        touch("scroll", MotionEvent.ACTION_UP, 100f, 402f, 1_160)
        assertFalse(UserGesture.consume("scroll", now = 1_300))
    }

    @Test
    fun longPressIsNotATap() {
        touch("long", MotionEvent.ACTION_DOWN, 50f, 50f, 1_000)
        touch("long", MotionEvent.ACTION_UP, 50f, 50f, 1_600)
        assertFalse(UserGesture.consume("long", now = 1_700))
    }

    @Test
    fun pinchAndCancelAreNotTaps() {
        touch("pinch", MotionEvent.ACTION_DOWN, 50f, 50f, 1_000)
        touch("pinch", MotionEvent.ACTION_POINTER_DOWN, 80f, 80f, 1_020)
        touch("pinch", MotionEvent.ACTION_UP, 50f, 50f, 1_100)
        assertFalse(UserGesture.consume("pinch", now = 1_200))

        touch("cancel", MotionEvent.ACTION_DOWN, 50f, 50f, 1_000)
        touch("cancel", MotionEvent.ACTION_CANCEL, 50f, 50f, 1_050)
        touch("cancel", MotionEvent.ACTION_UP, 50f, 50f, 1_100)
        assertFalse(UserGesture.consume("cancel", now = 1_200))
    }

    @Test
    fun upWithoutDownIsNotATap() {
        touch("orphan", MotionEvent.ACTION_UP, 10f, 10f, 1_000)
        assertFalse(UserGesture.consume("orphan", now = 1_100))
    }

    @Test
    fun recentDoesNotSpendTheSendTap() {
        UserGesture.tapped("paste", now = 1_000)
        assertTrue(UserGesture.recent("paste", now = 2_000))
        assertTrue(UserGesture.recent("paste", now = 3_000))
        assertTrue(UserGesture.consume("paste", now = 3_100))
        assertFalse(UserGesture.recent("paste", now = 1_000 + UserGesture.WINDOW_MS + 1))
        assertFalse(UserGesture.recent("never", now = 1_000))
    }
}
