package com.sersoluciones.flutter_pos_printer_platform.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Handler
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.sersoluciones.flutter_pos_printer_platform.R
import java.nio.charset.Charset
import java.util.*

class USBPrinterService private constructor(private var mHandler: Handler?) {
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIndent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null
    var state: Int = STATE_USB_NONE

    fun setHandler(handler: Handler?) {
        mHandler = handler
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return

            when (action) {
                ACTION_USB_PERMISSION -> synchronized(this) {
                    // Permission sonucu
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
                        // Güvenli isim kullanımı
                        val name = usbDevice.deviceName ?: "Unknown USB Device"
                        val msgTitle = mContext?.getString(R.string.user_refuse_perm)
                            ?: "Permission denied"
                        Toast.makeText(context, "$msgTitle: $name", Toast.LENGTH_LONG).show()
                        state = STATE_USB_NONE
                        mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // Cihaz ayrıldığında bağlantıyı kapat
                    if (mUsbDevice != null) {
                        val msg = mContext?.getString(R.string.device_off)
                            ?: "Device disconnected"
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        closeConnectionIfExists()
                        mUsbDevice = null
                        state = STATE_USB_NONE
                        mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                    }
                }

                // usb attach dalı boş bırakıldı isteğe bağlı işlenebilir
            }
        }
    }

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext?.getSystemService(Context.USB_SERVICE) as? UsbManager
        mPermissionIndent = if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(
                mContext, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), 0)
        }

        // Receiver kaydı
        mContext?.registerReceiver(
            mUsbDeviceReceiver,
            IntentFilter(ACTION_USB_PERMISSION).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
        )

        Log.v(LOG_TAG, "ESC/POS Printer initialized")
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
                    mContext?.getString(R.string.not_usb_manager) ?: "USB Manager not available",
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
                for (usbDevice in deviceList) {
                    if (usbDevice.vendorId == vendorId && usbDevice.productId == productId) {
                        Log.v(
                            LOG_TAG,
                            "Request for device: vendor_id: $vendorId, product_id: $productId"
                        )
                        mUSBManager?.requestPermission(usbDevice, mPermissionIndent)
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
        if (mUsbDevice == null) {
            Log.e(LOG_TAG, "USB Device is not initialized")
            return false
        }
        if (mUSBManager == null) {
            Log.e(LOG_TAG, "USB Manager is not initialized")
            return false
        }
        mUsbDeviceConnection?.let { return true }

        val usbInterface = mUsbDevice!!.getInterface(0)
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT
            ) {
                val conn = mUSBManager!!.openDevice(mUsbDevice)
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
                mUsbDeviceConnection?.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
            }
        }.start()
        return true
    }

    fun printRawData(data: String): Boolean {
        if (!openConnection()) return false
        Thread {
            synchronized(printLock) {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                mUsbDeviceConnection?.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
            }
        }.start()
        return true
    }

    fun printBytes(bytes: ArrayList<Int>): Boolean {
        if (!openConnection()) return false
        Thread {
            synchronized(printLock) {
                val chunkSize = mEndPoint?.maxPacketSize ?: return@run
                val byteData = ByteArray(bytes.size) { i -> bytes[i].toByte() }
                var offset = 0
                while (offset < byteData.size) {
                    val len = minOf(chunkSize, byteData.size - offset)
                    mUsbDeviceConnection?.bulkTransfer(mEndPoint, byteData, offset, len, 100000)
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