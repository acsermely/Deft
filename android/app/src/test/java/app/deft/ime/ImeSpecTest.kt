package app.deft.ime

import app.deft.bridge.BridgeArgs
import app.deft.catalog.BridgeCatalog
import app.deft.catalog.Capability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeSpecTest {
    // android.text.InputType values, written out so the test reads like the platform.
    private val text = 0x1
    private val number = 0x2
    private val phone = 0x3
    private val datetime = 0x4
    private val multiLine = 0x20000
    private val uri = 0x10
    private val email = 0x20
    private val webEmail = 0xd0
    private val password = 0x80
    private val visiblePassword = 0x90
    private val webPassword = 0xe0
    private val numberPassword = 0x10
    private val capSentences = 0x4000

    @Test
    fun passwordFieldsAreDetected() {
        assertTrue(ImeSpec.isPassword(text or password))
        assertTrue(ImeSpec.isPassword(text or visiblePassword))
        assertTrue(ImeSpec.isPassword(text or webPassword))
        assertTrue(ImeSpec.isPassword(number or numberPassword))
        // Flags on top of a password variation do not hide it.
        assertTrue(ImeSpec.isPassword(text or password or capSentences))
        assertTrue(ImeSpec.isNumberPassword(number or numberPassword))
        assertFalse(ImeSpec.isNumberPassword(text or password))
        for (plain in listOf(0, text, text or email, text or uri, text or multiLine, number, phone, datetime)) {
            assertFalse("$plain", ImeSpec.isPassword(plain))
        }
        // 0x10 is a number password but a URI in a text field.
        assertFalse(ImeSpec.isPassword(text or uri))
    }

    @Test
    fun inputTypesAreMapped() {
        assertEquals("text", ImeSpec.inputTypeName(text))
        assertEquals("text", ImeSpec.inputTypeName(0))
        assertEquals("text", ImeSpec.inputTypeName(text or capSentences))
        assertEquals("multiline", ImeSpec.inputTypeName(text or multiLine))
        assertEquals("email", ImeSpec.inputTypeName(text or email))
        assertEquals("email", ImeSpec.inputTypeName(text or webEmail))
        assertEquals("url", ImeSpec.inputTypeName(text or uri))
        assertEquals("number", ImeSpec.inputTypeName(number))
        assertEquals("number", ImeSpec.inputTypeName(datetime))
        assertEquals("phone", ImeSpec.inputTypeName(phone))
        assertEquals("password", ImeSpec.inputTypeName(text or password))
        assertEquals("password", ImeSpec.inputTypeName(number or numberPassword))
    }

    @Test
    fun actionsAreMapped() {
        assertEquals("go", ImeSpec.actionName(2))
        assertEquals("search", ImeSpec.actionName(3))
        assertEquals("send", ImeSpec.actionName(4))
        assertEquals("next", ImeSpec.actionName(5))
        assertEquals("done", ImeSpec.actionName(6))
        for (other in listOf(0, 1, 7)) assertEquals("none", ImeSpec.actionName(other))
        // IME_FLAG_NO_ENTER_ACTION: Enter types a new line.
        assertEquals("none", ImeSpec.actionName(6 or 0x40000000))
        // Other flags in imeOptions do not change the action.
        assertEquals("search", ImeSpec.actionName(3 or 0x02000000))
        for (name in listOf("go", "search", "send", "next", "done")) {
            assertEquals(name, ImeSpec.actionName(ImeSpec.actionId(name)!!))
        }
        assertNull(ImeSpec.actionId("none"))
    }

    @Test
    fun startPayloadCarriesNoText() {
        val payload = Json.parseToJsonElement(ImeSpec.startPayload(text or email, 5, "com.example.mail")).jsonObject
        assertEquals(setOf("inputType", "action", "package"), payload.keys)
        assertEquals("email", payload["inputType"]!!.jsonPrimitive.content)
        assertEquals("next", payload["action"]!!.jsonPrimitive.content)
        assertEquals("com.example.mail", payload["package"]!!.jsonPrimitive.content)
        val noPackage = Json.parseToJsonElement(ImeSpec.startPayload(text, 0, null)).jsonObject
        assertEquals("", noPackage["package"]!!.jsonPrimitive.content)
    }

    @Test
    fun keysAreMapped() {
        assertEquals(66, ImeSpec.keyCode("enter"))
        assertEquals(67, ImeSpec.keyCode("backspace"))
        assertEquals(61, ImeSpec.keyCode("tab"))
        assertEquals(21, ImeSpec.keyCode("left"))
        assertEquals(22, ImeSpec.keyCode("right"))
        assertEquals(19, ImeSpec.keyCode("up"))
        assertEquals(20, ImeSpec.keyCode("down"))
        for (bad in listOf(null, "", "Enter", "home", "power", "volume_up")) assertNull(ImeSpec.keyCode(bad))
    }

    private fun args(vararg pairs: Pair<String, JsonPrimitive>) = JsonObject(mapOf(*pairs))

    @Test
    fun deleteCountIsLimited() {
        assertEquals(1, ImeSpec.deleteCount(args("n" to JsonPrimitive(1))))
        assertEquals(1000, ImeSpec.deleteCount(args("n" to JsonPrimitive(1000))))
        assertEquals(3, ImeSpec.deleteCount(args("n" to JsonPrimitive("3"))))
        for (bad in listOf(JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(1001), JsonPrimitive(1.5), JsonPrimitive("x"), JsonPrimitive(true))) {
            assertNull("$bad", ImeSpec.deleteCount(args("n" to bad)))
        }
        assertNull(ImeSpec.deleteCount(JsonObject(emptyMap())))
    }

    @Test
    fun heightIsLimited() {
        assertEquals(100, ImeSpec.heightDp(args("dp" to JsonPrimitive(100))))
        assertEquals(600, ImeSpec.heightDp(args("dp" to JsonPrimitive(600))))
        assertEquals(240, ImeSpec.heightDp(args("dp" to JsonPrimitive(240))))
        for (bad in listOf(99, 601, 0, -5)) assertNull("$bad", ImeSpec.heightDp(args("dp" to JsonPrimitive(bad))))
        assertNull(ImeSpec.heightDp(args("dp" to JsonPrimitive(240.5))))
        // Never more than 70% of the screen.
        assertEquals(600, ImeSpec.heightPx(200, 3f, 2400))
        assertEquals(1680, ImeSpec.heightPx(600, 3f, 2400))
        assertEquals(700, ImeSpec.heightPx(600, 3f, 1000))
    }

    @Test
    fun textIsLimited() {
        assertEquals("hi", ImeSpec.textArg(args("text" to JsonPrimitive("hi"))))
        assertEquals("", ImeSpec.textArg(args("text" to JsonPrimitive(""))))
        assertEquals("a".repeat(1000), ImeSpec.textArg(args("text" to JsonPrimitive("a".repeat(1000)))))
        assertNull(ImeSpec.textArg(args("text" to JsonPrimitive("a".repeat(1001)))))
        assertNull(ImeSpec.textArg(args("text" to JsonPrimitive(5))))
        assertNull(ImeSpec.textArg(JsonObject(emptyMap())))
        // The central schema refuses long text before the module sees it.
        assertNotNull(BridgeArgs.validate("ime.commitText", args("text" to JsonPrimitive("a".repeat(1001)))))
        assertNull(BridgeArgs.validate("ime.commitText", args("text" to JsonPrimitive("abc"))))
        assertNotNull(BridgeArgs.validate("ime.commitText", JsonObject(emptyMap())))
        assertNotNull(BridgeArgs.validate("ime.sendKey", JsonObject(emptyMap())))
        assertNotNull(BridgeArgs.validate("ime.setHeight", JsonObject(emptyMap())))
        assertNotNull(BridgeArgs.validate("ime.deleteBefore", JsonObject(emptyMap())))
    }

    @Test
    fun imeMethodsOnlyFromTheKeyboardPage() {
        val methods = BridgeCatalog.methodsFor(Capability.IME)
        assertEquals(8, methods.size)
        for (m in methods) {
            assertTrue(m.startsWith("ime."))
            assertTrue(m, ImeSpec.methodAllowed(m, fromKeyboardPage = true))
            assertFalse(m, ImeSpec.methodAllowed(m, fromKeyboardPage = false))
        }
        // Other methods are not affected either way.
        assertTrue(ImeSpec.methodAllowed("clipboard.watchStart", fromKeyboardPage = true))
        assertTrue(ImeSpec.methodAllowed("clipboard.watchStart", fromKeyboardPage = false))
        assertTrue(ImeSpec.TYPING_METHODS.all { it in methods })
    }

    @Test
    fun typingIsLoggedAtMostOnceAMinute() {
        val t = 1_000_000L
        assertTrue(ImeSpec.shouldLogTyping(null, t))
        assertFalse(ImeSpec.shouldLogTyping(t, t + 59_999))
        assertTrue(ImeSpec.shouldLogTyping(t, t + 60_000))
        assertTrue(ImeSpec.shouldLogTyping(t, t - 1))
    }

    @Test
    fun defaultKeyboardSettingIsMatched() {
        val pkg = "app.deft.debug"
        val cls = "app.deft.ime.DeftInputMethodService"
        assertTrue(ImeSpec.isImeId("$pkg/app.deft.ime.DeftInputMethodService", pkg, cls))
        assertTrue(ImeSpec.isImeId("app.deft/.ime.DeftInputMethodService", "app.deft", cls))
        assertFalse(ImeSpec.isImeId(null, pkg, cls))
        assertFalse(ImeSpec.isImeId("", pkg, cls))
        assertFalse(ImeSpec.isImeId("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME", pkg, cls))
        assertFalse(ImeSpec.isImeId("$pkg/app.deft.ime.Other", pkg, cls))
        assertFalse(ImeSpec.isImeId("evil.$pkg/$cls", pkg, cls))
        assertFalse(ImeSpec.isImeId(pkg, pkg, cls))
    }

    @Test
    fun imeIsAnExclusiveSpecialRoleWithoutOngoingNotification() {
        val cap = Capability.IME
        assertEquals("role-ime", cap.exclusiveGroup)
        assertEquals(app.deft.catalog.Risk.SPECIAL, cap.risk)
        assertFalse(cap.persistentWhileActive)
        assertTrue(cap.available)
    }
}
