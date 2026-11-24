package app.deft.bridge

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HapticSpecTest {
    @Test
    fun everyKindMapsToAViewConstant() {
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, HapticSpec.constant("light"))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, HapticSpec.constant("medium"))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, HapticSpec.constant("selection"))
        assertEquals(HapticFeedbackConstants.CONFIRM, HapticSpec.constant("success"))
        assertEquals(HapticFeedbackConstants.REJECT, HapticSpec.constant("warning"))
    }

    @Test
    fun unknownKindsAreRefused() {
        assertNull(HapticSpec.constant("vibrate"))
        assertNull(HapticSpec.constant(""))
        assertNull(HapticSpec.constant("Light"))
    }

    @Test
    fun methodIsTheDocumentedName() {
        assertEquals("haptics.play", HapticSpec.METHOD)
    }
}
