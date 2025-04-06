package ru.wasiliysoft.tiqiaa_usb_demo

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        stopButton.isEnabled = false // Initially disabled
        startButton.isEnabled = false // Initially disabled until blaster is ready

        // Load patterns from JSON file
        loadPatternsFromJson()

        startButton.setOnClickListener {
            sendAllPatterns()
        }

        stopButton.setOnClickListener {
            transmissionJob?.cancel()
            Toast.makeText(this, "Transmission stopped", Toast.LENGTH_SHORT).show()
            transmissionStatus.text = getString(R.string.transmission_stopped)
            startButton.isEnabled = irBlaster?.isReady() == true
            stopButton.isEnabled = false
            progressBar.visibility = View.GONE
        }

        usbBroadCastReceiver = registerReceivers() // Make sure this is only called once
        initBlaster()
    }

    private fun initBlaster() {
        // First try built-in IR blaster
        irBlaster = BuiltInIrBlaster(this).also {
            if (it.init() && it.isAvailable()) {
                startButton.isEnabled = true
                Toast.makeText(this, "Using built-in IR blaster", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Fall back to USB IR blaster
        irBlaster = TiqiaaUsbDriver(this).also { usbDriver ->
            usbDriver.setInitializationListener(object : TiqiaaUsbDriver.InitializationListener {
                override fun onInitialized() {
                    runOnUiThread {
                        startButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "USB IR blaster ready", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onInitializationFailed(error: String) {
                    runOnUiThread {
                        if (usbDriver.findDevice() != null) {
                            Toast.makeText(this@MainActivity, "USB IR blaster failed: $error", Toast.LENGTH_LONG).show()
                        }
                        startButton.isEnabled = false
                        // Try falling back to built-in if available
                        irBlaster = BuiltInIrBlaster(this@MainActivity).also {
                            if (it.init() && it.isAvailable()) {
                                startButton.isEnabled = true
                                Toast.makeText(this@MainActivity, "Switched to built-in IR blaster", Toast.LENGTH_SHORT).show()
                            } else {
                                irBlaster = null
                                Toast.makeText(this@MainActivity, "No IR blaster available", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            })
            usbDriver.init() // Starts async permission request if needed
        }
    }

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

        if (irBlaster == null || !irBlaster!!.isReady()) {
            Toast.makeText(this, "IR blaster not ready", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        transmissionStatus.text = getString(R.string.transmitting)
        startButton.isEnabled = false
        stopButton.isEnabled = true

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
                            patternsCountText.text = getString(R.string.patterns_loaded, progressBar.max)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Transmission failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
                transmissionStatus.text = getString(R.string.transmission_complete_in_ms, overallDuration)
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
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
            // Removed ACTION_USB_PERMISSION since it's handled in TiqiaaUsbDriver
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
                    if (irBlaster !is BuiltInIrBlaster) {
                        Toast.makeText(context, "USB device inserted", Toast.LENGTH_SHORT).show()
                        irBlaster?.deinit() // Clean up any existing instance
                        initBlaster() // Reinitialize to handle the new device
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (irBlaster is TiqiaaUsbDriver) {
                        irBlaster?.deinit()
                        irBlaster = null
                        Toast.makeText(context, "USB device removed", Toast.LENGTH_SHORT).show()
                        initBlaster() // Try to fall back to built-in
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