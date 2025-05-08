package com.sersoluciones.flutter_pos_printer_platform

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothConstants
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.BluetoothService
import com.sersoluciones.flutter_pos_printer_platform.usb.USBPrinterService
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*

class FlutterPosPrinterPlatformPlugin : FlutterPlugin,
    MethodChannel.MethodCallHandler,
    PluginRegistry.RequestPermissionsResultListener,
    PluginRegistry.ActivityResultListener,
    ActivityAware {

    private val TAG = "FlutterPosPrinterPlatformPlugin"

    private var binaryMessenger: BinaryMessenger? = null
    private var channel: MethodChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    private var eventUSBSink: EventChannel.EventSink? = null

    private var context: Context? = null
    private var currentActivity: Activity? = null

    private var adapter: USBPrinterService? = null
    private var bluetoothService: BluetoothService? = null

    private var isScan = false
    private var isBle = false
    private var requestPermissionBT = false

    private val usbHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val sink = eventUSBSink ?: return
            when (msg.what) {
                USBPrinterService.STATE_USB_CONNECTED -> sink.success(2)
                USBPrinterService.STATE_USB_CONNECTING -> sink.success(1)
                USBPrinterService.STATE_USB_NONE -> sink.success(0)
            }
        }
    }

    private val bluetoothHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val sink = eventSink ?: return
            val status = BluetoothService.bluetoothConnection?.state ?: 99
            when (msg.what) {
                BluetoothConstants.MESSAGE_STATE_CHANGE -> when (status) {
                    BluetoothConstants.STATE_CONNECTED -> {
                        Log.w(TAG, "BT CONNECTED")
                        (msg.obj as? MethodChannel.Result)?.success(true)
                        sink.success(2)
                        bluetoothService?.removeReconnectHandlers()
                    }
                    BluetoothConstants.STATE_CONNECTING -> sink.success(1)
                    BluetoothConstants.STATE_NONE -> {
                        sink.success(0)
                        bluetoothService?.autoConnectBt()
                    }
                    BluetoothConstants.STATE_FAILED -> {
                        (msg.obj as? MethodChannel.Result)?.success(false)
                        sink.success(0)
                    }
                }
            }
        }
    }

    // FlutterPlugin  ————————————————————————————————————————————————
    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        binaryMessenger = binding.binaryMessenger
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        eventSink = null
        eventUSBSink = null
        bluetoothService?.setHandler(null)
        adapter?.setHandler(null)
    }

    // ActivityAware  ——————————————————————————————————————————————
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        val ctx = binding.activity.applicationContext
        context = ctx
        currentActivity = binding.activity

        channel = MethodChannel(binaryMessenger!!, methodChannel).apply {
            setMethodCallHandler(this@FlutterPosPrinterPlatformPlugin)
        }

        EventChannel(binaryMessenger!!, eventChannelBT).setStreamHandler(object: EventChannel.StreamHandler {
            override fun onListen(args: Any?, sink: EventChannel.EventSink) { eventSink = sink }
            override fun onCancel(args: Any?) { eventSink = null }
        })
        EventChannel(binaryMessenger!!, eventChannelUSB).setStreamHandler(object: EventChannel.StreamHandler {
            override fun onListen(args: Any?, sink: EventChannel.EventSink) { eventUSBSink = sink }
            override fun onCancel(args: Any?) { eventUSBSink = null }
        })

        adapter = USBPrinterService.getInstance(usbHandler).apply { init(ctx) }
        bluetoothService = BluetoothService.getInstance(bluetoothHandler)

        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
        bluetoothService.setActivity(currentActivity)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        currentActivity = null
        bluetoothService.setActivity(null)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        bluetoothService.setActivity(currentActivity)
        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        currentActivity = null
        bluetoothService.setActivity(null)
    }

    // PermissionsResultListener —————————————————————————————————————————
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        Log.d(TAG, "onRequestPermissionsResult: $requestCode")
        when (requestCode) {
            PERMISSION_ALL -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                val ctx = context ?: return false
                if (!allGranted) {
                    Toast.makeText(ctx, R.string.not_permissions, Toast.LENGTH_LONG).show()
                } else if (isScan) {
                    if (isBle) bluetoothService?.scanBleDevice(channel!!)
                    else bluetoothService?.scanBluDevice(channel!!)
                }
                return true
            }
        }
        return false
    }

    // ActivityResultListener ———————————————————————————————————————————
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == PERMISSION_ENABLE_BLUETOOTH) {
            requestPermissionBT = false
            if (resultCode == Activity.RESULT_OK && isScan) {
                if (isBle) bluetoothService?.scanBleDevice(channel!!)
                else bluetoothService?.scanBluDevice(channel!!)
            }
        }
        return true
    }

    // MethodCallHandler ———————————————————————————————————————————————
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        isScan = false
        when (call.method) {
            "getBluetoothList"     -> startBluetoothScan(result, false)
            "getBluetoothLeList"   -> startBluetoothScan(result, true)
            "onStartConnection"    -> startBluetoothConnection(call, result)
            "disconnect"           -> { bluetoothService?.bluetoothDisconnect(); result.success(true) }
            "sendDataByte"         -> {
                val list = call.argument<ArrayList<Int>>("bytes") ?: arrayListOf()
                val byteArray = list.map { it.toByte() }.toByteArray()
                result.success(bluetoothService?.sendDataByte(byteArray))
            }
            "sendText"             -> {
                call.argument<String>("text")?.let {
                    bluetoothService?.sendData(it)
                }
                result.success(true)
            }
            "getList"              -> getUSBDeviceList(result)
            "connectPrinter"       -> connectPrinter(call, result)
            "close"                -> { adapter?.closeConnectionIfExists(); result.success(true) }
            "printText"            -> {
                call.argument<String>("text")?.let {
                    adapter?.printText(it)
                }
                result.success(true)
            }
            "printRawData"         -> {
                call.argument<String>("raw")?.let {
                    adapter?.printRawData(it)
                }
                result.success(true)
            }
            "printBytes"           -> {
                val bytes = call.argument<ArrayList<Int>>("bytes") ?: arrayListOf()
                result.success(adapter?.printBytes(bytes))
            }
            else                   -> result.notImplemented()
        }
    }

    // ———————————————————————————————————————————————— Helper Metotlar ————————————————————————————————————————————————
    private fun startBluetoothScan(result: MethodChannel.Result, ble: Boolean) {
        isBle = ble; isScan = true
        if (verifyIsBluetoothIsOn()) {
            bluetoothService?.setHandler(bluetoothHandler)
            if (ble) bluetoothService?.scanBleDevice(channel!!)
            else    bluetoothService?.scanBluDevice(channel!!)
            result.success(null)
        } else result.success(null)
    }

    private fun startBluetoothConnection(call: MethodCall, result: MethodChannel.Result) {
        val address = call.argument<String>("address") ?: return result.success(false)
        val ble     = call.argument<Boolean>("isBle") ?: false
        val auto    = call.argument<Boolean>("autoConnect") ?: false
        if (verifyIsBluetoothIsOn()) {
            bluetoothService?.setHandler(bluetoothHandler)
            bluetoothService?.onStartConnection(currentActivity!!, address, result, ble, auto)
        } else result.success(false)
    }

    private fun getUSBDeviceList(result: MethodChannel.Result) {
        val list = adapter?.deviceList
            ?.map { usb ->
                mapOf(
                    "name"        to usb.deviceName,
                    "manufacturer" to usb.manufacturerName,
                    "product"     to usb.productName,
                    "deviceId"    to usb.deviceId.toString(),
                    "vendorId"    to usb.vendorId.toString(),
                    "productId"   to usb.productId.toString()
                )
            } ?: emptyList()
        result.success(list)
    }

    private fun connectPrinter(call: MethodCall, result: MethodChannel.Result) {
        val vendor  = call.argument<Int>("vendor")  ?: return result.success(false)
        val product = call.argument<Int>("product") ?: return result.success(false)
        adapter?.setHandler(usbHandler)
        result.success(adapter?.selectDevice(vendor, product) ?: false)
    }

    // … geri kalan (verifyIsBluetoothIsOn, checkPermissions, vs.) olduğu gibi kalır …

    companion object {
        const val PERMISSION_ALL = 1
        const val PERMISSION_ENABLE_BLUETOOTH = 999
        const val methodChannel    = "com.sersoluciones.flutter_pos_printer_platform"
        const val eventChannelBT   = "$methodChannel/bt_state"
        const val eventChannelUSB  = "$methodChannel/usb_state"
    }
}