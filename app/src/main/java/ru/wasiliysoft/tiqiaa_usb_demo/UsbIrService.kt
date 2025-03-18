package ru.wasiliysoft.tiqiaa_usb_demo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.os.Build
import android.util.Log

private const val LOG_TAG = "UsbIrService"

const val ACTION_USB_PERMISSION = "ru.wasiliysoft.tiqiaa.USB_PERMISSION"

// By WasiliySoft 21.09.2023 v1.1.0
class UsbIrService private constructor(
    private val mConnection: UsbDeviceConnection,
    private val epOUT: UsbEndpoint,
) {
    companion object {
        private var INSTANCE: UsbIrService? = null

        private var _usbPackCnt: Byte = 1
        private var _cmdCnt: Byte = 0
        fun getCmdId(): Byte {
            if (_cmdCnt < 127) _cmdCnt++ else _cmdCnt = 1
            return _cmdCnt
        }

        fun getUsbPackId(): Byte {
            if (_usbPackCnt < 15) _usbPackCnt++ else _usbPackCnt = 1
            return _usbPackCnt
        }

        fun getFrequencyId(freq: Int): Int {
            val tiqIrFreqTable = intArrayOf(
                38000, 37900, 37917, 36000, 40000, 39700, 35750, 36400, 36700, 37000,
                37700, 38380, 38400, 38462, 38740, 39200, 42000, 43600, 44000, 33000,
                33500, 34000, 34500, 35000, 40500, 41000, 41500, 42500, 43000, 45000
            )
            val tiqIrFreqTableSize = tiqIrFreqTable.size

            return if (freq > 255) {
                val index = tiqIrFreqTable.indexOf(freq)
                if (index == -1) 0 else index
            } else {
                if (freq < tiqIrFreqTableSize) freq else 0
            }
        }

        fun getInstance(usbManager: UsbManager, device: UsbDevice): UsbIrService? {
            if (INSTANCE != null && !usbManager.deviceList.containsValue(device)) {
                INSTANCE?.close() // Close if device no longer exists
            }

            if (INSTANCE == null && isCompatibleDevice(device)) {
                var endpointOUT: UsbEndpoint? = null
                val usbInterface = device.getInterface(0)

                for (i in 0 until usbInterface.endpointCount) {
                    usbInterface.getEndpoint(i).let { endpoint ->
                        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            when (endpoint.direction) {
                                UsbConstants.USB_DIR_OUT -> endpointOUT = endpoint
                                else -> Log.e(LOG_TAG, "undefined endpoints direction")
                            }
                        }
                    }
                }

                if (endpointOUT == null) {
                    Log.e(LOG_TAG, "Failed setting endpoint")
                    INSTANCE = null
                    return null
                }

                val connection = usbManager.openDevice(device)
                if (connection == null || !connection.claimInterface(usbInterface, true)) {
                    Log.e(LOG_TAG, "open device FAIL!")
                    INSTANCE = null
                    return null
                }
                Log.d(LOG_TAG, "open device SUCCESS!")
                INSTANCE = UsbIrService(connection, endpointOUT!!)
            }
            return INSTANCE
        }
    }

    fun transmit(freq: Int, pattern: IntArray) {
        val maxPayloadPerFragment = epOUT.maxPacketSize - 5
        if (maxPayloadPerFragment <= 0) {
            Log.e(LOG_TAG, "Invalid maxPayloadPerFragment: $maxPayloadPerFragment")
            return
        }

        val tqIrWriteFragments = mutableListOf<Byte>().apply {
            add(83)                                 // 'S'
            add(84)                                 // 'T'
            add(getCmdId())                         // cmdId
            add(68)                                 // 'D' - Data transfer (cmd_type)
            add(getFrequencyId(freq).toByte())      // Frequency id
            addAll(consumeIrToByteCode(pattern))    // payload
            add(69)                                 // 'E'
            add(78)                                 // 'N'
        }.chunked(maxPayloadPerFragment)

        val cmdUsbPackId = getUsbPackId()
        val fragmentCount = tqIrWriteFragments.size
        val toTransfer = mutableListOf<List<Byte>>()

        tqIrWriteFragments.forEachIndexed { index, fragment ->
            val fragmentHeader = listOf(
                2,                  // ReportId = 2
                fragment.size + 3,  // packet size
                cmdUsbPackId,       // cmd id
                fragmentCount,      // total fragment count
                index + 1,          // fragment id
            ).map { it.toByte() }
            toTransfer.add(fragmentHeader.plus(fragment))
        }

        toReady()
        toTransfer.forEach {
            bulkTransfer(it)
        }
        toSleep()
    }

    private fun toReady() {
        bulkTransfer(listOf(2, 9, getUsbPackId(), 1, 1, 83, 84, getCmdId(), 83, 69, 78)) // 'ST', cmdId, 'S', 'EN'
    }

    private fun toSleep() {
        bulkTransfer(listOf(2, 9, getUsbPackId(), 1, 1, 83, 84, getCmdId(), 76, 69, 78)) // 'ST', cmdId, 'L', 'EN'
    }

    private fun bulkTransfer(data: List<Byte>) {
        val byteWrite = data.toByteArray()
        try {
            val result = mConnection.bulkTransfer(epOUT, byteWrite, byteWrite.size, 250)
            println("$result bytes written " + (byteWrite.size == result))
            if (result != byteWrite.size) {
                Log.e(LOG_TAG, "Bulk transfer failed, device may be disconnected")
                close()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Bulk transfer exception: ${e.message}")
            close()
            throw e
        }
    }

    fun close() {
        mConnection.close()
        INSTANCE = null
        Log.d(LOG_TAG, "UsbIrService closed")
    }
}

@SuppressLint("WrongConstant")
fun requestUsbPermissionForCompatibleDev(context: Context, usbManager: UsbManager) {
    for (device: UsbDevice in usbManager.deviceList.values) {
        if (isCompatibleDevice(device)) {
            val requestCode = 0
            val intent = Intent(ACTION_USB_PERMISSION)
            val piFlags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, requestCode, intent, piFlags)
            usbManager.requestPermission(device, pi)
        }
    }
}

fun consumeIrToByteCode(pattern: IntArray): ArrayList<Byte> {
    val usbTickPattern = pattern.map { it / 16 } // Convert microseconds to ticks
    val result = ArrayList<Byte>(usbTickPattern.size)
    for ((index, tickCount) in usbTickPattern.withIndex()) {
        result.addAll(usbTickToUsbByteCode(tickCount, index % 2 == 0))
    }
    return result
}
fun usbTickToUsbByteCode(tickCount: Int, isOn: Boolean): List<Byte> {
    val maximumBlockSize = 127
    var remainingTicks = tickCount
    val result = ArrayList<Byte>()
    while (remainingTicks > 0) {
        val sendBlockSize = minOf(remainingTicks, maximumBlockSize)
        remainingTicks -= sendBlockSize
        val byteValue = if (isOn) (sendBlockSize or 128) else sendBlockSize
        result.add(byteValue.toByte())
    }
    return result
}

fun isCompatibleDevice(device: UsbDevice): Boolean {
    if (device.interfaceCount != 1) return false
    if (device.getInterface(0).endpointCount == 0) return false
    if (device.vendorId == 4292 && device.productId == 33896) return true
    if (device.vendorId == 1118 && device.productId == 33896) return true
    return false
}