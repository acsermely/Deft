package app.deft.bridge

import app.deft.background.BackgroundPolicy
import app.deft.bridge.NotificationsSpec as S
import app.deft.background.BackgroundTriggers
import app.deft.catalog.BridgeCatalog
import app.deft.catalog.Capability
import app.deft.catalog.Risk
import app.deft.registry.DeftManifestParser
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsSpecTest {
    private fun obj(json: String): JsonObject = BridgeJson.parseToJsonElement(json).jsonObject

    private val locked = S.Privacy(locked = true, hideSensitiveOnLockScreen = true)

    private fun raw(
        visibility: Int = S.VISIBILITY_PRIVATE,
        channel: Int = S.NO_OVERRIDE,
        hasPublic: Boolean = false,
    ) = S.Raw(
        key = "0|com.chat|7|null|10123",
        pkg = "com.chat",
        appLabel = "Chat",
        title = "Anna",
        text = "See you at 6",
        subText = "work",
        time = 1_700_000_000_000,
        category = "msg",
        ongoing = false,
        visibility = visibility,
        channelLockscreen = channel,
        publicTitle = "Chat",
        publicText = "1 new message",
        hasPublicVersion = hasPublic,
        actions = listOf(S.Action("Reply", reply = true), S.Action("Mark as read", reply = false)),
    )

    @Test
    fun launcherOwnNotificationsAreNeverForwarded() {
        assertFalse(S.forwardable("app.deft", "app.deft"))
        assertFalse(S.forwardable("", "app.deft"))
        assertTrue(S.forwardable("com.chat", "app.deft"))
    }

    @Test
    fun shapeHasTheDocumentedFields() {
        val p = S.shape(raw(), S.Privacy.UNLOCKED)
        assertEquals(
            setOf("key", "package", "appLabel", "title", "text", "subText", "time", "category", "ongoing", "redacted", "actions"),
            p.keys,
        )
        assertEquals("com.chat", p["package"]!!.jsonPrimitive.content)
        assertEquals("Anna", p["title"]!!.jsonPrimitive.content)
        assertEquals("See you at 6", p["text"]!!.jsonPrimitive.content)
        assertFalse(p["redacted"]!!.jsonPrimitive.boolean)
        val actions = p["actions"]!!.jsonArray
        assertEquals(2, actions.size)
        assertEquals(0, actions[0].jsonObject["index"]!!.jsonPrimitive.int)
        assertTrue(actions[0].jsonObject["reply"]!!.jsonPrimitive.boolean)
        assertEquals(1, actions[1].jsonObject["index"]!!.jsonPrimitive.int)
        assertFalse(actions[1].jsonObject["reply"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun optionalFieldsAreLeftOutAndMissingTextIsNull() {
        val r = raw().copy(title = null, text = "  ", subText = null, category = null, appLabel = null, actions = emptyList())
        val p = S.shape(r, S.Privacy.UNLOCKED)
        assertFalse("subText" in p)
        assertFalse("category" in p)
        assertEquals(JsonNull, p["title"])
        assertEquals(JsonNull, p["text"])
        assertEquals("com.chat", p["appLabel"]!!.jsonPrimitive.content)
    }

    @Test
    fun secretIsAlwaysRedacted() {
        for (privacy in listOf(S.Privacy.UNLOCKED, locked)) {
            val p = S.shape(raw(visibility = S.VISIBILITY_SECRET, hasPublic = true), privacy)
            assertTrue(p["redacted"]!!.jsonPrimitive.boolean)
            assertEquals(JsonNull, p["title"])
            assertEquals(JsonNull, p["text"])
            assertFalse("subText" in p)
            // Who and when stay: the holder still sees that Chat posted something.
            assertEquals("Chat", p["appLabel"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun channelSetToSecretByTheUserWins() {
        assertEquals(S.VISIBILITY_SECRET, S.effectiveVisibility(S.VISIBILITY_PUBLIC, S.VISIBILITY_SECRET))
        assertEquals(S.VISIBILITY_PRIVATE, S.effectiveVisibility(S.VISIBILITY_PRIVATE, S.NO_OVERRIDE))
        assertEquals(S.VISIBILITY_PRIVATE, S.effectiveVisibility(S.VISIBILITY_PRIVATE, S.VISIBILITY_PUBLIC))
        val p = S.shape(raw(visibility = S.VISIBILITY_PUBLIC, channel = S.VISIBILITY_SECRET), S.Privacy.UNLOCKED)
        assertTrue(p["redacted"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun privateFollowsTheLockScreenOnlyWhileLockedAndHiding() {
        assertEquals(S.Redaction.NONE, S.redaction(raw(), S.Privacy.UNLOCKED))
        assertEquals(S.Redaction.NONE, S.redaction(raw(), S.Privacy(locked = true, hideSensitiveOnLockScreen = false)))
        assertEquals(S.Redaction.NONE, S.redaction(raw(), S.Privacy(locked = false, hideSensitiveOnLockScreen = true)))
        assertEquals(S.Redaction.HIDDEN, S.redaction(raw(), locked))
        assertEquals(S.Redaction.PUBLIC_VERSION, S.redaction(raw(hasPublic = true), locked))
        assertEquals(S.Redaction.NONE, S.redaction(raw(visibility = S.VISIBILITY_PUBLIC), locked))

        val pub = S.shape(raw(hasPublic = true), locked)
        assertTrue(pub["redacted"]!!.jsonPrimitive.boolean)
        assertEquals("Chat", pub["title"]!!.jsonPrimitive.content)
        assertEquals("1 new message", pub["text"]!!.jsonPrimitive.content)
        assertFalse("subText" in pub)
    }

    @Test
    fun textsAreCapped() {
        val long = "x".repeat(5000)
        val r = raw().copy(
            title = long, text = long, subText = long, appLabel = long,
            actions = (1..15).map { S.Action(long, reply = false) },
        )
        val p = S.shape(r, S.Privacy.UNLOCKED)
        assertEquals(S.LABEL_MAX, p["title"]!!.jsonPrimitive.content.length)
        assertEquals(S.TEXT_MAX, p["text"]!!.jsonPrimitive.content.length)
        assertEquals(S.LABEL_MAX, p["subText"]!!.jsonPrimitive.content.length)
        assertEquals(S.LABEL_MAX, p["appLabel"]!!.jsonPrimitive.content.length)
        val actions = p["actions"]!!.jsonArray
        assertEquals(S.MAX_ACTIONS, actions.size)
        assertEquals(S.ACTION_TITLE_MAX, actions[0].jsonObject["title"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun capNeverSplitsASurrogatePair() {
        val s = "a".repeat(9) + "😀" + "b"
        assertEquals("a".repeat(9), S.cap(s, 10))
        assertEquals("a".repeat(9) + "😀", S.cap(s, 11))
        assertNull(S.cap("   ", 10))
        assertNull(S.cap(null, 10))
        assertEquals("hi", S.cap("  hi \n", 10))
    }

    @Test
    fun removedPayloadNamesKnownReasons() {
        assertEquals("""{"key":"k","reason":"dismissed"}""", S.removedPayload("k", 2))
        assertEquals("""{"key":"k","reason":"click"}""", S.removedPayload("k", 1))
        assertEquals("""{"key":"k","reason":"app"}""", S.removedPayload("k", 8))
        assertEquals("""{"key":"k"}""", S.removedPayload("k", 999))
        assertEquals("""{"key":"k"}""", S.removedPayload("k", null))
    }

    @Test
    fun repeatsWithTheSameContentAreDropped() {
        val recent = S.RecentPosts(capacity = 2)
        val a = S.shape(raw(), S.Privacy.UNLOCKED)
        val aLater = S.shape(raw().copy(time = 1_800_000_000_000), S.Privacy.UNLOCKED)
        val b = S.shape(raw().copy(text = "Changed"), S.Privacy.UNLOCKED)
        assertFalse(recent.isRepeat("k", S.content(a)))
        assertTrue(recent.isRepeat("k", S.content(aLater)))
        assertFalse(recent.isRepeat("k", S.content(b)))
        recent.forget("k")
        assertFalse(recent.isRepeat("k", S.content(b)))
        // Bounded: the oldest key falls out.
        recent.isRepeat("k2", "x")
        recent.isRepeat("k3", "y")
        assertFalse(recent.isRepeat("k", S.content(b)))
    }

    @Test
    fun replyArgsAreChecked() {
        val ok = S.parseReply(obj("""{"key":"k","actionIndex":1,"text":"On my way"}"""))
        assertEquals(S.Parsed.Ok(S.Reply("k", 1, "On my way")), ok)
        for (json in listOf(
            """{"actionIndex":0,"text":"x"}""",
            """{"key":"","actionIndex":0,"text":"x"}""",
            """{"key":5,"actionIndex":0,"text":"x"}""",
            """{"key":"k","text":"x"}""",
            """{"key":"k","actionIndex":"0","text":"x"}""",
            """{"key":"k","actionIndex":-1,"text":"x"}""",
            """{"key":"k","actionIndex":1.5,"text":"x"}""",
            """{"key":"k","actionIndex":10,"text":"x"}""",
            """{"key":"k","actionIndex":0}""",
            """{"key":"k","actionIndex":0,"text":"   "}""",
            """{"key":"k","actionIndex":0,"text":7}""",
            """{"key":"k","actionIndex":0,"text":"${"x".repeat(1001)}"}""",
            """{"key":"${"k".repeat(1025)}","actionIndex":0,"text":"x"}""",
        )) {
            assertTrue(json, S.parseReply(obj(json)) is S.Parsed.Bad)
        }
        assertEquals(S.Parsed.Ok("a|b"), S.parseKey(obj("""{"key":"a|b"}""")))
    }

    @Test
    fun auditAtMostHourly() {
        assertTrue(S.auditDue(0, 5))
        assertFalse(S.auditDue(1_000, 1_000 + S.AUDIT_EVERY_MS - 1))
        assertTrue(S.auditDue(1_000, 1_000 + S.AUDIT_EVERY_MS))
        assertTrue(S.wakesBackground(ongoing = false))
        assertFalse(S.wakesBackground(ongoing = true))
    }

    @Test
    fun methodsBelongToNotificationsListenAndArgsAreBounded() {
        val cap = Capability.NOTIFICATIONS_LISTEN
        assertEquals(Risk.SPECIAL, cap.risk)
        assertEquals(S.GROUP, cap.exclusiveGroup)
        assertTrue(cap.available)
        assertTrue(cap.persistentWhileActive)
        for (m in listOf("notifications.active", "notifications.dismiss", "notifications.reply", "notifications.open")) {
            assertEquals(m, cap, BridgeCatalog.capabilityFor(m))
        }
        // The app's own notifications stay with notify.*.
        assertEquals(Capability.NOTIFICATIONS_POST, BridgeCatalog.capabilityFor("notify.post"))
        assertNotNull(BridgeArgs.validate("notifications.reply", obj("""{"key":"k","actionIndex":0}""")))
        assertNotNull(BridgeArgs.validate("notifications.reply", obj("""{"key":"k","actionIndex":0,"text":"${"x".repeat(1001)}"}""")))
        assertNull(BridgeArgs.validate("notifications.reply", obj("""{"key":"k","actionIndex":0,"text":"hi"}""")))
        assertNotNull(BridgeArgs.validate("notifications.dismiss", obj("""{}""")))
        assertNull(BridgeArgs.validate("notifications.open", obj("""{"key":"k"}""")))
    }

    @Test
    fun openNeedsTheAppOnScreenTheRestRunInBackground() {
        val cap = Capability.NOTIFICATIONS_LISTEN
        val none = obj("{}")
        assertTrue(BackgroundPolicy.needsForeground("notifications.open", none, cap))
        for (m in listOf("notifications.active", "notifications.dismiss", "notifications.reply")) {
            assertFalse(m, BackgroundPolicy.needsForeground(m, none, cap))
        }
    }

    @Test
    fun notificationIsADeclarableTrigger() {
        assertEquals("notification", BackgroundTriggers.NOTIFICATION)
        assertTrue(BackgroundTriggers.NOTIFICATION in DeftManifestParser.BACKGROUND_TRIGGERS)
        val c = BackgroundTriggers.Candidate("a", declaresCapability = true, granted = true, triggers = listOf("notification"))
        assertTrue(BackgroundTriggers.wants(c, BackgroundTriggers.NOTIFICATION))
        assertFalse(BackgroundTriggers.wants(c.copy(granted = false), BackgroundTriggers.NOTIFICATION))
    }
}
