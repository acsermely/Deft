package app.deft.ui.bluetooth

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.deft.bridge.BridgeAccessoryPicks

class BluetoothEnableActivity : ComponentActivity() {
    private var requestId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ)
    }

    @Deprecated("startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestId.isNotBlank()) {
            BridgeAccessoryPicks.completeBleEnable(requestId, resultCode == Activity.RESULT_OK)
        }
        finish()
    }

    override fun onDestroy() {
        if (requestId.isNotBlank()) {
            BridgeAccessoryPicks.completeBleEnable(requestId, false)
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        private const val REQ = 7
    }
}
