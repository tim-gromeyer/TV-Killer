package ru.wasiliysoft.tiqiaa_usb_demo

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private var irBlaster: IrBlaster? = null
    private lateinit var usbBroadCastReceiver: BroadcastReceiver
    private lateinit var irPatterns: List<IrPattern>
    private lateinit var progressBar: ProgressBar
    private lateinit var patternsCountText: TextView
    private lateinit var transmissionStatus: TextView
    private lateinit var stopButton: Button
    private lateinit var startButton: Button
    private var transmissionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        progressBar = findViewById(R.id.progressBar)
        patternsCountText = findViewById(R.id.patternsCountText)
        transmissionStatus = findViewById(R.id.transmissionStatus)
        startButton = findViewById<Button>(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        stopButton.isEnabled = false // Initially disabled

        // Load patterns from JSON file
        loadPatternsFromJson()

        startButton.setOnClickListener {
            sendAllPatterns()
        }

        stopButton.setOnClickListener {
            transmissionJob?.cancel()
            Toast.makeText(this, "Transmission stopped", Toast.LENGTH_SHORT).show()
            transmissionStatus.text = getString(R.string.transmission_stopped)
            startButton.isEnabled = true
            stopButton.isEnabled = false
            progressBar.visibility = View.GONE
        }

        initBlaster()
    }

    private fun initBlaster() {
        // First try to use built-in IR blaster
        irBlaster = BuiltInIrBlaster(this).also {
            if (it.init() && it.isAvailable()) {
                Toast.makeText(this, "Using built-in IR blaster", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Fall back to USB IR blaster
        usbBroadCastReceiver = registerReceivers()
        irBlaster = TiqiaaUsbDriver(this).also {
            if (it.init()) {
                Toast.makeText(this, "Using USB IR blaster", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // No IR blaster available
        Toast.makeText(this, "No IR blaster available", Toast.LENGTH_LONG).show()
        irBlaster = null
    }

    /** Loads IR patterns from JSON and converts them to microseconds */
    private fun loadPatternsFromJson() {
        try {
            val inputStream = resources.openRawResource(R.raw.ir_patterns)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val loadedPatterns = Gson().fromJson(jsonString, Array<IrPattern>::class.java).filterNotNull().toList()
            inputStream.close()

            irPatterns = loadedPatterns.map { irPattern ->
                val convertedPatterns = irPattern.patterns.map { convertPatternToMicroseconds(it) }
                val convertedMute = convertPatternToMicroseconds(irPattern.mute)
                IrPattern(irPattern.designation, convertedPatterns, convertedMute)
            }

            val totalPatterns = irPatterns.sumOf { it.patterns.size } + irPatterns.size
            patternsCountText.text = getString(R.string.patterns_loaded, totalPatterns)
            progressBar.max = totalPatterns

        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load IR patterns", Toast.LENGTH_LONG).show()
            irPatterns = emptyList()
            patternsCountText.text = getString(R.string.patterns_loaded, 0)
        }
    }

    /** Converts a pattern from carrier cycles to microseconds */
    private fun convertPatternToMicroseconds(pattern: Pattern?): Pattern? {
        if (pattern == null) return null
        val frequency = pattern.frequency
        val periodUs = 1_000_000.0 / frequency
        val convertedPattern = pattern.pattern.map { cycles ->
            (cycles * periodUs).roundToInt()
        }.toIntArray()
        return Pattern(frequency, convertedPattern, pattern.comment)
    }

    private fun sendAllPatterns() {
        if (irPatterns.isEmpty()) {
            Toast.makeText(this, "No patterns loaded", Toast.LENGTH_SHORT).show()
            return
        }

        if (irBlaster == null) {
            Toast.makeText(this, "IR blaster not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        transmissionStatus.text = getString(R.string.transmitting)
        startButton.isEnabled = false // Disable Transmit button
        stopButton.isEnabled = true // Enable Stop button

        transmissionJob = CoroutineScope(Dispatchers.IO).launch {
            var currentProgress = 0
            val overallStartTime = System.currentTimeMillis()

            irPatterns.forEach { irPattern ->
                irPattern.patterns.filterNotNull().forEach { pattern ->
                    withContext(Dispatchers.Main) {
                        transmissionStatus.text = getString(
                            R.string.transmitting_pattern_of,
                            currentProgress + 1,
                            progressBar.max
                        )
                    }
                    try {
                        val success = irBlaster!!.sendIrSignal(pattern.frequency, pattern.pattern)
                        if (!success) throw Exception("Failed to send IR signal")
                        withContext(Dispatchers.Main) {
                            currentProgress++
                            progressBar.progress = currentProgress
                            // Optionally keep patternsCountText for total patterns only
                            patternsCountText.text = getString(R.string.patterns_loaded, progressBar.max)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "Transmission failed: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            transmissionStatus.text = getString(R.string.transmission_failed)
                            startButton.isEnabled = true
                            stopButton.isEnabled = false
                            progressBar.visibility = View.GONE
                        }
                        return@launch
                    }
                }
            }

            val overallDuration = System.currentTimeMillis() - overallStartTime
            withContext(Dispatchers.Main) {
                transmissionStatus.text =
                    getString(R.string.transmission_complete_in_ms, overallDuration)
                startButton.isEnabled = true
                stopButton.isEnabled = false
                progressBar.visibility = View.GONE
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers(): BroadcastReceiver {
        val usbBroadCastReceiver = getUsbBroadCastReceiver()
        val intentFilters = arrayOf(
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED),
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            IntentFilter(TiqiaaUsbDriver.ACTION_USB_PERMISSION)
        )

        intentFilters.forEach { filter ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbBroadCastReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(usbBroadCastReceiver, filter)
            }
        }
        return usbBroadCastReceiver
    }

    private fun getUsbBroadCastReceiver() = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // Only handle USB events if we're not using built-in IR blaster
                    if (irBlaster !is BuiltInIrBlaster) {
                        Toast.makeText(context, "USB device inserted", Toast.LENGTH_SHORT).show()
                        irBlaster = TiqiaaUsbDriver(context).also {
                            it.init()
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (irBlaster is TiqiaaUsbDriver) {
                        irBlaster?.deinit()
                        irBlaster = null
                        Toast.makeText(context, "USB device removed", Toast.LENGTH_SHORT).show()
                        // Try to fall back to built-in IR blaster if available
                        initBlaster()
                    }
                }
                TiqiaaUsbDriver.ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                            irBlaster = TiqiaaUsbDriver(context).also {
                                if (it.init()) {
                                    Toast.makeText(context, "USB device permission granted and driver initialized", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to initialize USB driver after permission", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            Toast.makeText(context, "USB permission denied", Toast.LENGTH_LONG).show()
                            // Try to fall back to built-in IR blaster if available
                            initBlaster()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        irBlaster?.deinit()
        if (this::usbBroadCastReceiver.isInitialized) {
            try {
                unregisterReceiver(usbBroadCastReceiver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

}