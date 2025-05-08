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
import androidx.core.app.ActivityCompat
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.*
import com.sersoluciones.flutter_pos_printer_platform.usb.USBPrinterService
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*

class FlutterPosPrinterPlatformPlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    PluginRegistry.RequestPermissionsResultListener, PluginRegistry.ActivityResultListener,
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

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        binaryMessenger = binding.binaryMessenger
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        eventSink = null
        eventUSBSink = null
        bluetoothService?.setHandler(null)
        adapter?.setHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        val ctx = binding.activity.applicationContext
        context = ctx
        currentActivity = binding.activity

        channel = MethodChannel(binaryMessenger!!, methodChannel).apply {
            setMethodCallHandler(this@FlutterPosPrinterPlatformPlugin)
        }

        EventChannel(binaryMessenger!!, eventChannelBT).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                eventSink = sink
            }
            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })

        EventChannel(binaryMessenger!!, eventChannelUSB).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                eventUSBSink = sink
            }
            override fun onCancel(arguments: Any?) {
                eventUSBSink = null
            }
        })

        adapter = USBPrinterService.getInstance(usbHandler).apply { init(ctx) }
        bluetoothService = BluetoothService.getInstance(bluetoothHandler)

        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
        bluetoothService?.setActivity(currentActivity)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        currentActivity = null
        bluetoothService?.setActivity(null)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        bluetoothService?.setActivity(currentActivity)
        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        currentActivity = null
        bluetoothService?.setActivity(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        isScan = false
        when (call.method) {
            "getBluetoothList" -> startBluetoothScan(result, false)
            "getBluetoothLeList" -> startBluetoothScan(result, true)
            "onStartConnection" -> startBluetoothConnection(call, result)
            "disconnect" -> {
                bluetoothService?.apply {
                    setHandler(bluetoothHandler)
                    bluetoothDisconnect()
                }
                result.success(true)
            }
            "sendDataByte" -> {
                val list = call.argument<ArrayList<Int>>("bytes") ?: arrayListOf()
                bluetoothService?.apply {
                    setHandler(bluetoothHandler)
                    result.success(sendDataByte(list.toByteArray()))
                } ?: result.success(false)
            }
            "sendText" -> {
                val text = call.argument<String>("text") ?: ""
                bluetoothService?.sendData(text)
                result.success(true)
            }
            "getList" -> getUSBDeviceList(result)
            "connectPrinter" -> connectPrinter(call, result)
            "close" -> {
                adapter?.apply { setHandler(usbHandler); closeConnectionIfExists() }
                result.success(true)
            }
            "printText" -> {
                call.argument<String>("text")?.let {
                    adapter?.apply { setHandler(usbHandler); printText(it) }
                    result.success(true)
                }
            }
            "printRawData" -> {
                call.argument<String>("raw")?.let {
                    adapter?.apply { setHandler(usbHandler); printRawData(it) }
                    result.success(true)
                }
            }
            "printBytes" -> {
                val bytes = call.argument<ArrayList<Int>>("bytes")
                adapter?.apply { setHandler(usbHandler); result.success(printBytes(bytes ?: arrayListOf())) }
            }
            else -> result.notImplemented()
        }
    }

    // … (diğer metodlar aynı kalır, içlerinde context kullanıldığı yerde şöyle yap) …

    private fun someToastExample() {
        val ctx = context ?: return
        Toast.makeText(ctx, "Example", Toast.LENGTH_SHORT).show()
    }

    // Gerektiğinde benzer pattern’le tüm context erişimlerini düzeltin

    // Bluetooth ve USB yardımcı metodları…

    companion object {
        const val PERMISSION_ALL = 1
        const val PERMISSION_ENABLE_BLUETOOTH = 999
        const val methodChannel = "com.sersoluciones.flutter_pos_printer_platform"
        const val eventChannelBT = "com.sersoluciones.flutter_pos_printer_platform/bt_state"
        const val eventChannelUSB = "com.sersoluciones.flutter_pos_printer_platform/usb_state"
    }
}