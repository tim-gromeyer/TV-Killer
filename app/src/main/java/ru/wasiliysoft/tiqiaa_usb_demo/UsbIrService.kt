package ru.wasiliysoft.tiqiaa_usb_demo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import ru.wasiliysoft.tiqiaa_usb_demo.TiqiaaUsbDriver.Companion.ACTION_USB_PERMISSION
import java.nio.ByteBuffer

const val LOG_TAG = "UsbIrService"
// const val ACTION_USB_PERMISSION = "ru.wasiliysoft.tiqiaa.USB_PERMISSION"

/**
 * A service class to control a USB IR blaster on Android.
 * Manages device communication and IR signal transmission.
 */
class UsbIrService private constructor(
    private val mConnection: UsbDeviceConnection,
    private val epOUT: UsbEndpoint,
    private val epIN: UsbEndpoint
) {
    companion object {
        private var INSTANCE: UsbIrService? = null
        private const val MAX_PACKET_SIZE = 1024
        private const val MAX_FRAG_SIZE = 56
        private const val MAX_CMD_ID = 127
        private const val MAX_PACKET_IDX = 15
        private const val IR_TICK_SIZE = 16 // 16 microseconds per tick
        private const val MAX_IR_BLOCK_SIZE = 127 // Maximum ticks per block

        private var cmdId: Byte = 1
        private var packetIdx: Byte = 0

        /** Frequency table for IR transmission */
        private val tiqIrFreqTable = intArrayOf(
            38000, 37900, 37917, 36000, 40000, 39700, 35750, 36400, 36700, 37000,
            37700, 38380, 38400, 38462, 38740, 39200, 42000, 43600, 44000, 33000,
            33500, 34000, 34500, 35000, 40500, 41000, 41500, 42500, 43000, 45000
        )

        /** Generates a unique command ID */
        fun getCmdId(): Byte {
            if (cmdId < MAX_CMD_ID) cmdId++ else cmdId = 1
            return cmdId
        }

        /** Generates a unique packet index */
        fun getPacketIdx(): Byte {
            if (packetIdx < MAX_PACKET_IDX) packetIdx++ else packetIdx = 1
            return packetIdx
        }

        /** Maps frequency to an ID or index in the frequency table */
        fun getFrequencyId(freq: Int): Int {
            return if (freq > 255) {
                tiqIrFreqTable.indexOf(freq).takeIf { it != -1 } ?: 0
            } else {
                if (freq < tiqIrFreqTable.size) freq else 0
            }
        }

        /** Checks if the device is a compatible IR blaster */
        fun isCompatibleDevice(device: UsbDevice): Boolean {
            val vid = device.vendorId
            val pid = device.productId
            return (vid == 0x10C4 && pid == 0x8468) || (vid == 0x045E && pid == 0x8468)
        }

        /**
         * Creates or retrieves a singleton instance of UsbIrService for a compatible device.
         */
        fun getInstance(usbManager: UsbManager, device: UsbDevice): UsbIrService? {
            if (INSTANCE != null && !usbManager.deviceList.containsValue(device)) {
                INSTANCE?.close()
            }

            if (INSTANCE == null && isCompatibleDevice(device)) {
                var endpointOUT: UsbEndpoint? = null
                var endpointIN: UsbEndpoint? = null
                val usbInterface = device.getInterface(0)

                for (i in 0 until usbInterface.endpointCount) {
                    usbInterface.getEndpoint(i).let { endpoint ->
                        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            when (endpoint.direction) {
                                UsbConstants.USB_DIR_OUT -> endpointOUT = endpoint
                                UsbConstants.USB_DIR_IN -> endpointIN = endpoint
                            }
                        }
                    }
                }

                if (endpointOUT == null || endpointIN == null) {
                    Log.e(LOG_TAG, "Failed to find bulk endpoints")
                    return null
                }

                val connection = usbManager.openDevice(device)
                if (connection == null || !connection.claimInterface(usbInterface, true)) {
                    Log.e(LOG_TAG, "Failed to open or claim device")
                    connection?.close()
                    return null
                }

                Log.d(LOG_TAG, "Device opened successfully")
                INSTANCE = UsbIrService(connection, endpointOUT!!, endpointIN!!)
            }
            return INSTANCE
        }
    }

    /**
     * Sends a command to the device (e.g., 'O' for output, 'L' for idle).
     */
    private fun sendCmd(cmdType: Byte, cmdId: Byte): Boolean {
        val pack = byteArrayOf(
            'S'.toByte(), 'T'.toByte(), // Start signature
            cmdId,
            cmdType,
            'E'.toByte(), 'N'.toByte()  // End signature
        )
        return sendReport2(pack, pack.size)
    }

    /**
     * Sends a report (data packet) to the device, handling fragmentation.
     */
    private fun sendReport2(data: ByteArray, size: Int): Boolean {
        val fragBuf = ByteBuffer.allocate(61) // Report header + max frag size
        var rdPtr = 0
        var fragIdx = 0

        if (size <= 0 || size > MAX_PACKET_SIZE) {
            Log.e(LOG_TAG, "Invalid packet size: $size")
            return false
        }

        val fragCount = (size + MAX_FRAG_SIZE - 1) / MAX_FRAG_SIZE
        val packetIdx = getPacketIdx()

        while (rdPtr < size) {
            fragIdx++
            val fragSize = minOf(MAX_FRAG_SIZE, size - rdPtr)
            fragBuf.clear()
            fragBuf.put(2) // Report ID
            fragBuf.put((fragSize + 3).toByte()) // Fragment size
            fragBuf.put(packetIdx)
            fragBuf.put(fragCount.toByte())
            fragBuf.put(fragIdx.toByte())
            fragBuf.put(data, rdPtr, fragSize)

            val bytesToSend = 5 + fragSize // Header + data
            val result = mConnection.bulkTransfer(epOUT, fragBuf.array(), bytesToSend, 1000)
            if (result != bytesToSend) {
                Log.e(LOG_TAG, "Failed to send fragment: $result != $bytesToSend")
                return false
            }
            rdPtr += fragSize
        }
        return readResponse()
    }

    /**
     * Reads a response from the device, handling fragmented packets.
     */
    private fun readResponse(): Boolean {
        val buffer = ByteArray(63)
        var fragCount = 1
        var fragIdx = 0
        val packBuf = mutableListOf<Byte>()

        while (fragIdx < fragCount) {
            val result = mConnection.bulkTransfer(epIN, buffer, buffer.size, 500)
            if (result < 5) {
                Log.e(LOG_TAG, "Incomplete response header: $result bytes")
                return false
            }

            val reportId = buffer[0]
            val fragSize = buffer[1]
            val packetIdx = buffer[2]
            val fragCountFromHeader = buffer[3]
            val fragIdxFromHeader = buffer[4]

            if (fragIdx == 0) {
                fragCount = fragCountFromHeader.toInt()
            }
            fragIdx++

            val dataStart = 5
            val dataSize = result - dataStart
            packBuf.addAll(buffer.copyOfRange(dataStart, dataStart + dataSize).toList())
        }

        Log.d(LOG_TAG, "Response received: ${packBuf.toByteArray().toList()}")
        return true
    }

    /**
     * Transmits an IR signal with the specified frequency and pattern.
     * @param freq Frequency in Hz
     * @param pattern Array of pulse/space durations in microseconds
     */
    fun transmit(freq: Int, pattern: IntArray): Boolean {
        val cmdId = getCmdId()

        // Send 'O' (Output) command to prepare device
        if (!sendCmd('O'.toByte(), cmdId)) {
            Log.e(LOG_TAG, "Failed to send 'O' command")
            return false
        }

        // Prepare IR data packet
        val irData = mutableListOf<Byte>()
        irData.add('S'.toByte())
        irData.add('T'.toByte())
        irData.add(cmdId)
        irData.add('D'.toByte()) // Data command
        irData.add(getFrequencyId(freq).toByte())
        irData.addAll(encodeIrPattern(pattern))
        irData.add('E'.toByte())
        irData.add('N'.toByte())

        // Send IR data
        if (!sendReport2(irData.toByteArray(), irData.size)) {
            Log.e(LOG_TAG, "Failed to send IR data")
            return false
        }

        // Return to idle mode
        return sendCmd('L'.toByte(), getCmdId())
    }

    /**
     * Encodes an IR pattern into a byte sequence for transmission.
     * @param pattern Array of pulse/space durations in microseconds
     */
    private fun encodeIrPattern(pattern: IntArray): List<Byte> {
        val result = mutableListOf<Byte>()
        for (i in pattern.indices) {
            val isOn = i % 2 == 0 // Even indices are pulses (ON), odd are spaces (OFF)
            val duration = pattern[i]
            val ticks = duration / IR_TICK_SIZE
            var remainingTicks = ticks

            while (remainingTicks > 0) {
                val blockSize = minOf(remainingTicks, MAX_IR_BLOCK_SIZE)
                val byteValue = if (isOn) (blockSize or 128).toByte() else blockSize.toByte()
                result.add(byteValue)
                remainingTicks -= blockSize
            }
        }
        return result
    }

    /** Closes the USB connection and cleans up resources */
    fun close() {
        // mConnection.releaseInterface(mConnection.)
        mConnection.close()
        INSTANCE = null
        Log.d(LOG_TAG, "UsbIrService closed")
    }
}

/**
 * Requests USB permission for compatible devices.
 */
@SuppressLint("WrongConstant")
fun requestUsbPermissionForCompatibleDev(context: Context, usbManager: UsbManager) {
    for (device in usbManager.deviceList.values) {
        if (UsbIrService.isCompatibleDevice(device)) {
            val intent = Intent(ACTION_USB_PERMISSION)
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pi = PendingIntent.getBroadcast(context, 0, intent, piFlags)
            usbManager.requestPermission(device, pi)
        }
    }
}