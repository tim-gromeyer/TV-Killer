package ru.wasiliysoft.tiqiaa_usb_demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Parcelable
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private var irService: IIrService? = null

    private val usbManager: UsbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private lateinit var usbBroadCastReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val btn = findViewById<Button>(R.id.button)
        val pattern = intArrayOf(
            8880, 4420, 580, 520, 580, 520, 580, 1670, 580, 520, 580, 520, 580, 520, 580, 520,
            580, 520, 580, 1620, 580, 1620, 580, 520, 580, 1620, 580, 1670, 580, 1620, 580, 1620,
            580, 1620, 580, 1620, 580, 520, 580, 520, 580, 520, 580, 1670, 580, 520, 580, 520,
            580, 520, 580, 520, 580, 1620, 580, 1620, 580, 1620, 580, 520, 580, 1670, 530, 1670,
            580, 1620, 580
        )
        btn.setOnClickListener {
            irService?.transmit(38000, pattern)
        }
        initIrBlaster()
    }

    private fun initIrBlaster() {
        usbBroadCastReceiver = registerReceivers()
        if (usbManager.deviceList.isNotEmpty()) {
            requestUsbPermissionForCompatibleDev(this, usbManager)
        } else {
            Toast.makeText(this, "insert tiqiaa usb ir blaster", Toast.LENGTH_LONG).show()
        }
    }

    private fun registerReceivers(): BroadcastReceiver {
        val usbBroadCastReceiver = getUsbBroadCastReceiver()
        arrayOf(
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED),
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            IntentFilter(ACTION_USB_PERMISSION)
        ).map { registerReceiver(usbBroadCastReceiver, it) }
        return usbBroadCastReceiver
    }

    private fun getUsbBroadCastReceiver() = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> irService = null
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    requestUsbPermissionForCompatibleDev(applicationContext, usbManager)
                }

                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = intent.getParcelableExtra<Parcelable>("device") as UsbDevice?
                        if (intent.getBooleanExtra("permission", false) && device != null) {
                            try {
                                irService = UsbIrService.getInstance(usbManager, device)
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        if (this::usbBroadCastReceiver.isInitialized) {
            try {
                unregisterReceiver(usbBroadCastReceiver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}