package ru.wasiliysoft.tiqiaa_usb_demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private var irService: UsbIrService? = null
    private val usbManager: UsbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private lateinit var usbBroadCastReceiver: BroadcastReceiver
    private lateinit var irPatterns: List<IrPattern>
    private lateinit var progressBar: ProgressBar
    private lateinit var patternsCountText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        progressBar = findViewById(R.id.progressBar)
        patternsCountText = findViewById(R.id.patternsCountText)
        val btn = findViewById<Button>(R.id.button)

        // Load patterns from JSON file
        loadPatternsFromJson()

        btn.setOnClickListener {
            sendAllPatterns()
        }

        initIrBlaster()
    }

    private fun loadPatternsFromJson() {
        try {
            val inputStream = resources.openRawResource(R.raw.ir_patterns)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            irPatterns = Gson().fromJson(jsonString, Array<IrPattern>::class.java).toList()
            inputStream.close()

            // Calculate and display total number of patterns
            val totalPatterns = irPatterns.sumOf { it.patterns.size } + irPatterns.size
            patternsCountText.text = "Patterns loaded: $totalPatterns"
            progressBar.max = totalPatterns

        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load IR patterns", Toast.LENGTH_LONG).show()
            irPatterns = emptyList()
            patternsCountText.text = "Patterns loaded: 0"
        }
    }

    private fun sendAllPatterns() {
        if (irPatterns.isEmpty()) {
            Toast.makeText(this, "No patterns loaded", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            Toast.makeText(this@MainActivity, "Starting transmission of all patterns", Toast.LENGTH_SHORT).show()

            var currentProgress = 0

            // Send all regular patterns
            irPatterns.forEach { irPattern ->
                irPattern.patterns.forEach { pattern ->
                    val startTime = System.currentTimeMillis()
                    irService?.transmit(pattern.frequency, pattern.pattern)
                    val duration = System.currentTimeMillis() - startTime
                    currentProgress++
                    progressBar.progress = currentProgress
                    patternsCountText.setText("Send packet number $currentProgress, took $duration ms")
                    delay(100)
                }
            }

            Toast.makeText(this@MainActivity, "Finished transmitting all patterns", Toast.LENGTH_SHORT).show()
            progressBar.visibility = View.GONE
        }
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
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Toast.makeText(context, "USB device inserted", Toast.LENGTH_SHORT).show()
                    requestUsbPermissionForCompatibleDev(applicationContext, usbManager)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    irService?.close()
                    irService = null
                    Toast.makeText(context, "USB device removed", Toast.LENGTH_SHORT).show()
                }
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = intent.getParcelableExtra<Parcelable>("device") as UsbDevice?
                        if (intent.getBooleanExtra("permission", false) && device != null) {
                            try {
                                irService = UsbIrService.getInstance(usbManager, device)
                                Toast.makeText(context, "USB device permission granted", Toast.LENGTH_SHORT).show()
                            } catch (e: IOException) {
                                e.printStackTrace()
                                Toast.makeText(context, "Failed to initialize USB device", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "USB permission denied", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
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