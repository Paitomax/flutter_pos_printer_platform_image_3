package com.sersoluciones.flutter_pos_printer_platform.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.sersoluciones.flutter_pos_printer_platform.R
import java.io.FileInputStream
import java.nio.charset.Charset

class USBPrinterService private constructor(private var mHandler: Handler?) {
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIntent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null
    var state: Int = STATE_USB_NONE

    fun setHandler(handler: Handler?) {
        mHandler = handler
    }

    private val mUsbDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return

            when (action) {
                ACTION_USB_PERMISSION -> synchronized(this) {
                    val usbDevice: UsbDevice? =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (usbDevice == null) {
                        Log.e(LOG_TAG, "USB_PERMISSION received but no EXTRA_DEVICE")
                        state = STATE_USB_NONE
                        mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                        return
                    }
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    if (granted) {
                        Log.i(
                            LOG_TAG,
                            "Permission granted for device ${usbDevice.deviceId}, " +
                            "vendor=${usbDevice.vendorId}, product=${usbDevice.productId}"
                        )
                        mUsbDevice = usbDevice
                        state = STATE_USB_CONNECTED
                        mHandler?.obtainMessage(STATE_USB_CONNECTED)?.sendToTarget()
                    } else {
                        val name = usbDevice.deviceName ?: "Unknown USB Device"
                        val msgTitle = mContext
                            ?.getString(R.string.user_refuse_perm)
                            ?: "Permission denied"
                        Toast.makeText(context, "$msgTitle: $name", Toast.LENGTH_LONG).show()
                        state = STATE_USB_NONE
                        mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (mUsbDevice != null) {
                        val msg = mContext
                            ?.getString(R.string.device_off)
                            ?: "Device disconnected"
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        closeConnectionIfExists()
                        mUsbDevice = null
                        state = STATE_USB_NONE
                        mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                    }
                }
            }
        }
    }

    /**
     * Initialize service: register receiver and prepare UsbManager/Permission intent.
     */
    fun init(context: Context) {
        mContext = context
        mUSBManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        mPermissionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), 0)
        }

        // register receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(mUsbDeviceReceiver, filter)
        Log.v(LOG_TAG, "ESC/POS Printer initialized")
    }

    /**
     * Clean up: unregister the receiver.
     */
    fun dispose() {
        mContext?.unregisterReceiver(mUsbDeviceReceiver)
        mContext = null
        mUSBManager = null
    }

    fun closeConnectionIfExists() {
        mUsbDeviceConnection?.let { conn ->
            mUsbInterface?.let { iface -> conn.releaseInterface(iface) }
            conn.close()
        }
        mUsbInterface = null
        mEndPoint = null
        mUsbDevice = null
        mUsbDeviceConnection = null
    }

    val deviceList: List<UsbDevice>
        get() {
            if (mUSBManager == null) {
                Toast.makeText(
                    mContext,
                    mContext?.getString(R.string.not_usb_manager)
                        ?: "USB Manager not available",
                    Toast.LENGTH_LONG
                ).show()
                return emptyList()
            }
            return ArrayList(mUSBManager!!.deviceList.values)
        }

    fun selectDevice(vendorId: Int, productId: Int): Boolean {
        if (mUsbDevice == null ||
            mUsbDevice!!.vendorId != vendorId ||
            mUsbDevice!!.productId != productId
        ) {
            synchronized(printLock) {
                closeConnectionIfExists()
                for (device in deviceList) {
                    if (device.vendorId == vendorId && device.productId == productId) {
                        Log.v(
                            LOG_TAG,
                            "Request for device: vendor_id: $vendorId, product_id: $productId"
                        )
                        mUSBManager?.requestPermission(device, mPermissionIntent)
                        state = STATE_USB_CONNECTING
                        mHandler?.obtainMessage(STATE_USB_CONNECTING)?.sendToTarget()
                        return true
                    }
                }
                return false
            }
        } else {
            mHandler?.obtainMessage(state)?.sendToTarget()
            return true
        }
    }

    private fun openConnection(): Boolean {
        val device = mUsbDevice ?: run {
            Log.e(LOG_TAG, "USB Device is not initialized")
            return false
        }
        val manager = mUSBManager ?: run {
            Log.e(LOG_TAG, "USB Manager is not initialized")
            return false
        }
        mUsbDeviceConnection?.let { return true }

        val usbInterface = device.getInterface(0)
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT
            ) {
                val conn = manager.openDevice(device)
                if (conn == null) {
                    Log.e(LOG_TAG, "Failed to open USB Connection")
                    return false
                }
                Toast.makeText(
                    mContext,
                    mContext?.getString(R.string.connected_device) ?: "Device connected",
                    Toast.LENGTH_SHORT
                ).show()
                return if (conn.claimInterface(usbInterface, true)) {
                    mUsbInterface = usbInterface
                    mEndPoint = ep
                    mUsbDeviceConnection = conn
                    true
                } else {
                    conn.close()
                    Log.e(LOG_TAG, "Failed to claim interface")
                    false
                }
            }
        }
        return true
    }

    fun printText(text: String): Boolean {
        if (!openConnection()) return false
        Thread {
            synchronized(printLock) {
                val bytes = text.toByteArray(Charset.forName("UTF-8"))
                mUsbDeviceConnection?.bulkTransfer(mEndPoint, bytes, bytes.size, 100_000)
            }
        }.start()
        return true
    }

    fun printRawData(data: String): Boolean {
        if (!openConnection()) return false
        Thread {
            synchronized(printLock) {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                mUsbDeviceConnection?.bulkTransfer(mEndPoint, bytes, bytes.size, 100_000)
            }
        }.start()
        return true
    }

    fun printBytes(bytes: List<Int>): Boolean {
        if (!openConnection()) return false
        Thread {
            synchronized(printLock) {
                val endpoint = mEndPoint ?: return@Thread
                val chunkSize = endpoint.maxPacketSize
                val byteData = ByteArray(bytes.size) { i -> bytes[i].toByte() }
                var offset = 0
                while (offset < byteData.size) {
                    val len = minOf(chunkSize, byteData.size - offset)
                    mUsbDeviceConnection?.bulkTransfer(endpoint, byteData, offset, len, 100_000)
                    offset += len
                }
            }
        }.start()
        return true
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var mInstance: USBPrinterService? = null
        private const val LOG_TAG = "ESC POS Printer"
        private const val ACTION_USB_PERMISSION = "com.flutter_pos_printer.USB_PERMISSION"

        const val STATE_USB_NONE = 0
        const val STATE_USB_CONNECTING = 2
        const val STATE_USB_CONNECTED = 3

        private val printLock = Any()

        fun getInstance(handler: Handler): USBPrinterService {
            if (mInstance == null) {
                mInstance = USBPrinterService(handler)
            }
            return mInstance!!
        }
    }
}