package ru.wasiliysoft.tiqiaa_usb_demo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TiqiaaUsbDriver(private val context: Context) {
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var packetIdx = 0
    private var cmdId: Byte = 1

    companion object {
        private const val TAG = "TiqiaaUsbDriver"
        const val ACTION_USB_PERMISSION = "com.example.tiqiaa.USB_PERMISSION"
        private const val MAX_USB_FRAG_SIZE = 56
        private const val MAX_USB_PACKET_SIZE = 1024
        private const val MAX_USB_PACKET_IDX = 15
        private const val MAX_CMD_ID = 0x7F
        private const val WRITE_REPORT_ID = 2
        private const val PACK_START_SIGN = 0x5453 // "ST"
        private const val PACK_END_SIGN = 0x4E45 // "EN"
        private const val CMD_IDLE_MODE = 'L'.code.toByte()
        private const val CMD_SEND_MODE = 'S'.code.toByte()
        private const val CMD_DATA = 'D'.code.toByte()

        private val compatibleDevices = listOf(
            UsbDeviceId(0x10C4, 0x8468),
            UsbDeviceId(0x45E, 0x8468)
        )

        private val tiqIrFreqTable = listOf(
            38000, 37900, 37917, 36000, 40000, 39700, 35750, 36400, 36700, 37000,
            37700, 38380, 38400, 38462, 38740, 39200, 42000, 43600, 44000, 33000,
            33500, 34000, 34500, 35000, 40500, 41000, 41500, 42500, 43000, 45000
        )
    }

    data class UsbDeviceId(val vendor: Int, val product: Int)

    /**
     * Initializes the USB driver by finding the device, requesting permission if necessary,
     * and setting up the connection and endpoints.
     */
    fun init(): Boolean {
        device = findDevice()
        if (device == null) {
            Log.e(TAG, "No compatible USB device found")
            return false
        }

        if (!usbManager.hasPermission(device)) {
            if (!requestPermission()) {
                Log.e(TAG, "USB permission not granted")
                return false
            }
        }

        connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device")
            return false
        }

        val usbInterface = device!!.getInterface(0)
        connection!!.claimInterface(usbInterface, true)

        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> endpointIn = endpoint
                    UsbConstants.USB_DIR_OUT -> endpointOut = endpoint
                }
            }
        }

        if (endpointIn == null || endpointOut == null) {
            Log.e(TAG, "Failed to find bulk IN or OUT endpoints")
            deinit()
            return false
        }

        Log.d(TAG, "USB initialization successful, sending initial commands")
        return sendCmd(CMD_IDLE_MODE, getCmdId()) && sendCmd(CMD_SEND_MODE, getCmdId())
    }

    /**
     * Deinitializes the driver by setting the device to idle mode and closing the connection.
     */
    fun deinit() {
        sendCmd(CMD_IDLE_MODE, getCmdId())
        connection?.releaseInterface(device?.getInterface(0))
        connection?.close()
        connection = null
        device = null
        Log.d(TAG, "USB driver deinitialized")
    }

    /**
     * Sends an IR signal with the specified frequency and pulse durations.
     * @param freq IR carrier frequency in Hz
     * @param pulses List of pulse and space durations in microseconds (alternating pulse, space)
     */
    fun sendIrSignal(freq: Int, pulses: List<Int>): Boolean {
        Log.d(TAG, "sendIrSignal called with freq=$freq, pulses=${pulses.size} elements")
        if (!sendCmd(CMD_IDLE_MODE, getCmdId()) || !sendCmd(CMD_SEND_MODE, getCmdId())) {
            Log.e(TAG, "Failed to reset device state before sending IR signal")
            return false
        }
        Log.d(TAG, "Device reset to idle and send mode")

        val buf = ByteArray(1017) // Increased to 1017 bytes
        var bufSize = 0

        for (i in pulses.indices step 2) {
            val pulse = pulses[i]
            val space = if (i + 1 < pulses.size) pulses[i + 1] else 0
            Log.v(TAG, "Processing pulse=$pulse, space=$space at index $i")
            bufSize = writePulse(buf, bufSize, true, pulse)
            if (bufSize < 0) {
                Log.e(TAG, "Buffer overflow while writing pulse at index $i")
                return false
            }
            if (space > 0) {
                bufSize = writePulse(buf, bufSize, false, space)
                if (bufSize < 0) {
                    Log.e(TAG, "Buffer overflow while writing space at index ${i + 1}")
                    return false
                }
            }
        }

        Log.d(TAG, "Pulse buffer prepared, size=$bufSize")
        return sendIr(freq, buf, bufSize)
    }

    private fun findDevice(): UsbDevice? {
        val deviceList = usbManager.deviceList
        val foundDevice = deviceList.values.find { device ->
            compatibleDevices.any { it.vendor == device.vendorId && it.product == device.productId }
        }
        Log.d(TAG, "findDevice: ${if (foundDevice != null) "Device found" else "No device found"}")
        return foundDevice
    }

    private fun requestPermission(): Boolean {
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val latch = CountDownLatch(1)
        var permissionGranted = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action && device != null) {
                    permissionGranted = usbManager.hasPermission(device)
                    Log.d(TAG, "Permission request result: $permissionGranted")
                    latch.countDown()
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
        usbManager.requestPermission(device, permissionIntent)
        Log.d(TAG, "Permission requested for device")

        latch.await(10, TimeUnit.SECONDS)
        context.unregisterReceiver(receiver)
        return permissionGranted
    }

    private fun getCmdId(): Byte {
        return if (cmdId < MAX_CMD_ID) {
            cmdId++.toByte()
        } else {
            cmdId = 1
            cmdId.toByte()
        }.also { Log.v(TAG, "Generated cmdId: $it") }
    }

    private fun sendCmd(cmdType: Byte, cmdId: Byte): Boolean {
        val pack = ByteArray(6).apply {
            this[0] = (PACK_START_SIGN and 0xFF).toByte()
            this[1] = (PACK_START_SIGN shr 8).toByte()
            this[2] = cmdId
            this[3] = cmdType
            this[4] = (PACK_END_SIGN and 0xFF).toByte()
            this[5] = (PACK_END_SIGN shr 8).toByte()
        }
        Log.d(TAG, "Sending command type=$cmdType, cmdId=$cmdId")
        return sendReport2(pack, pack.size)
    }

    private fun writePulse(buf: ByteArray, offset: Int, isOn: Boolean, duration: Int): Int {
        var pulseLength = duration / 16
        var bufIndex = offset
        while (pulseLength > 127) {
            if (bufIndex >= buf.size) return -1
            buf[bufIndex] = ((if (isOn) 128 else 0) + 127).toByte()
            pulseLength -= 127
            bufIndex++
        }
        if (bufIndex >= buf.size) return -1
        buf[bufIndex] = ((if (isOn) 128 else 0) + pulseLength).toByte()
        Log.v(TAG, "writePulse: isOn=$isOn, duration=$duration, bufIndex=$bufIndex")
        return bufIndex + 1
    }

    private fun sendIr(freq: Int, buffer: ByteArray, bufSize: Int): Boolean {
        val cmdId = getCmdId()
        Log.d(TAG, "sendIr: freq=$freq, bufSize=$bufSize, cmdId=$cmdId")
        return sendIrCmd(freq, buffer, bufSize, cmdId)
    }

    private fun sendIrCmd(freq: Int, buffer: ByteArray, bufSize: Int, cmdId: Byte): Boolean {
        val packBuf = ByteArray(1024)
        val packHeaderSize = 5

        if (bufSize < 0 || bufSize + packHeaderSize + 2 > MAX_USB_PACKET_SIZE) {
            Log.e(TAG, "Invalid IR buffer size: $bufSize")
            return false
        }

        val irFreqId: Byte = if (freq > 255) {
            tiqIrFreqTable.indexOf(freq).toByte().takeIf { it >= 0 } ?: 0
        } else {
            if (freq < tiqIrFreqTable.size) freq.toByte() else run {
                Log.e(TAG, "Invalid frequency index $freq")
                return false
            }
        }

        packBuf[0] = (PACK_START_SIGN and 0xFF).toByte()
        packBuf[1] = (PACK_START_SIGN shr 8).toByte()
        packBuf[2] = cmdId
        packBuf[3] = CMD_DATA
        packBuf[4] = irFreqId
        System.arraycopy(buffer, 0, packBuf, 5, bufSize)
        val packSize = 5 + bufSize
        packBuf[packSize] = (PACK_END_SIGN and 0xFF).toByte()
        packBuf[packSize + 1] = (PACK_END_SIGN shr 8).toByte()

        Log.d(TAG, "sendIrCmd: prepared packet, size=${packSize + 2}, freqId=$irFreqId")
        return sendReport2(packBuf, packSize + 2)
    }

    private fun sendReport2(data: ByteArray, size: Int): Boolean {
        if (size <= 0 || size > MAX_USB_PACKET_SIZE) {
            Log.e(TAG, "Invalid data size for sendReport2: $size")
            return false
        }

        val fragCount = size / MAX_USB_FRAG_SIZE + if (size % MAX_USB_FRAG_SIZE != 0) 1 else 0
        packetIdx = (packetIdx % MAX_USB_PACKET_IDX) + 1
        var rdPtr = 0

        Log.d(TAG, "sendReport2: size=$size, fragCount=$fragCount, packetIdx=$packetIdx")

        for (fragIdx in 1..fragCount) {
            val fragSize = minOf(size - rdPtr, MAX_USB_FRAG_SIZE)
            val fragBuf = ByteArray(61).apply {
                this[0] = WRITE_REPORT_ID.toByte()
                this[1] = (fragSize + 3).toByte()
                this[2] = packetIdx.toByte()
                this[3] = fragCount.toByte()
                this[4] = fragIdx.toByte()
                System.arraycopy(data, rdPtr, this, 5, fragSize)
            }

            Log.v(TAG, "Sending fragment $fragIdx/$fragCount, size=${5 + fragSize}")
            val bytesWritten = connection!!.bulkTransfer(endpointOut, fragBuf, 5 + fragSize, 0)
            if (bytesWritten < 0) {
                Log.e(TAG, "Bulk transfer failed for fragment $fragIdx: $bytesWritten")
                return false
            }
            Log.d(TAG, "Fragment $fragIdx sent, bytesWritten=$bytesWritten")
            rdPtr += fragSize
        }

        Log.d(TAG, "All fragments sent, awaiting response")
        return recResponse()
    }

    private fun recResponse(): Boolean {
        val fragBuf = ByteArray(63)
        var fragCount = 1
        var fragIdx = 0

        Log.d(TAG, "recResponse: starting response reception")
        while (fragIdx < fragCount) {
            val bytesRead = connection!!.bulkTransfer(endpointIn, fragBuf, fragBuf.size, 5000)
            if (bytesRead < 0) {
                Log.e(TAG, "Error receiving response fragment ${fragIdx + 1}: $bytesRead")
                return false
            }
            if (bytesRead >= 5) {
                val fragCountFromHeader = fragBuf[3].toInt() and 0xFF
                val fragIdxFromHeader = fragBuf[4].toInt() and 0xFF
                if (fragIdx == 0) fragCount = fragCountFromHeader
                fragIdx++
                Log.d(TAG, "Received frag $fragIdxFromHeader/$fragCount, size=$bytesRead")
            } else {
                Log.w(TAG, "Received incomplete fragment, size=$bytesRead")
            }
        }
        Log.d(TAG, "recResponse: all fragments received successfully")
        return true
    }
}