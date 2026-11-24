package app.deft.ui.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.deft.bridge.BridgeAccessoryPicks

class UsbPermissionActivity : ComponentActivity() {
    private var requestId: String = ""
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            finishWith(granted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty()
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
        if (device == null) {
            finishWith(false)
            return
        }
        if (usbManager.hasPermission(device)) {
            finishWith(true)
            return
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true
        val pi = PendingIntent.getBroadcast(
            this,
            requestId.hashCode(),
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, pi)
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
        }
        if (requestId.isNotBlank()) {
            BridgeAccessoryPicks.completeUsbPermission(requestId, false)
        }
        super.onDestroy()
    }

    private fun finishWith(granted: Boolean) {
        if (requestId.isNotBlank()) {
            BridgeAccessoryPicks.completeUsbPermission(requestId, granted)
        }
        finish()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val ACTION_USB_PERMISSION = "app.deft.USB_PERMISSION"
    }
}
