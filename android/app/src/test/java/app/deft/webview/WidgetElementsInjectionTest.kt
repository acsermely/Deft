package app.deft.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WidgetElementsInjectionTest {
    private val tag = "<script type=\"module\" src=\"/_deft/v1/elements.js\"></script>"
    private val w = "<deft-widget app=\"myapp:a\" size=\"2x1\"></deft-widget>"

    @Test fun injectsBeforeHeadClose() {
        assertEquals("<html><head><title>x</title>$tag</head><body>$w</body></html>",
            LocalOriginPathHandler.injectWidgetElements("<html><head><title>x</title></head><body>$w</body></html>"))
    }

    @Test fun noHeadGoesAtBodyStart() {
        assertEquals("<body>$tag$w</body>", LocalOriginPathHandler.injectWidgetElements("<body>$w</body>"))
    }

    @Test fun bareFragmentGetsPrefix() {
        assertEquals(tag + w, LocalOriginPathHandler.injectWidgetElements(w))
    }

    @Test fun alreadyPresentUnchanged() {
        val h = "<head>$tag</head><body>$w</body>"
        assertSame(h, LocalOriginPathHandler.injectWidgetElements(h))
    }

    @Test fun noWidgetUnchanged() {
        val h = "<head></head><body></body>"
        assertSame(h, LocalOriginPathHandler.injectWidgetElements(h))
    }
}
