package app.deft.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UriIntakeTest {
    private val own = "app.deft"

    private fun provider(
        pkg: String = "com.example.sender",
        exported: Boolean = true,
        readProtected: Boolean = false,
        system: Boolean = false,
    ) = UriIntake.Provider(pkg, exported, readProtected, system)

    @Test
    fun ownAuthorityIsRefusedEvenWithAGrant() {
        assertFalse(UriIntake.allowed("content", provider(pkg = own, exported = false), own, granted = true))
        assertFalse(UriIntake.allowed("content", provider(pkg = own), own, granted = false))
    }

    @Test
    fun protectedProviderWithoutAGrantIsRefused() {
        // Contacts / SMS / call log: guarded by a permission the launcher holds.
        assertFalse(UriIntake.allowed("content", provider(pkg = "com.android.providers.contacts", readProtected = true, system = true), own, granted = false))
        assertFalse(UriIntake.allowed("content", provider(readProtected = true), own, granted = false))
    }

    @Test
    fun systemProviderWithoutPermissionNeedsAGrant() {
        // MediaStore declares no read permission but checks the caller's permissions in code.
        val media = provider(pkg = "com.android.providers.media.module", system = true)
        assertFalse(UriIntake.allowed("content", media, own, granted = false))
        assertTrue(UriIntake.allowed("content", media, own, granted = true))
    }

    @Test
    fun publicThirdPartyProviderIsAllowed() {
        assertTrue(UriIntake.allowed("content", provider(), own, granted = false))
    }

    @Test
    fun nonExportedProviderNeedsAGrant() {
        assertFalse(UriIntake.allowed("content", provider(exported = false), own, granted = false))
        assertTrue(UriIntake.allowed("content", provider(exported = false), own, granted = true))
    }

    @Test
    fun recordedGrantIsAllowed() {
        assertTrue(UriIntake.allowed("content", provider(readProtected = true), own, granted = true))
        assertTrue(UriIntake.allowed("content", null, own, granted = true))
    }

    @Test
    fun unknownProviderWithoutGrantAndOtherSchemesAreRefused() {
        assertFalse(UriIntake.allowed("content", null, own, granted = false))
        assertFalse(UriIntake.allowed("file", provider(), own, granted = true))
        assertFalse(UriIntake.allowed(null, provider(), own, granted = true))
    }
}
