package app.deft.account

import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeftSyncServiceTest {
    @Test
    fun onlyTheSystemMayDriveTheSyncAdapter() {
        assertTrue(DeftSyncService.allowedCaller(Process.SYSTEM_UID))
        assertFalse(DeftSyncService.allowedCaller(10_123))
        assertFalse(DeftSyncService.allowedCaller(0))
    }
}
