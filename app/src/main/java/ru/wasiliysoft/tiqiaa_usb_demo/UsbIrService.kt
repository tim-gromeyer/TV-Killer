package ru.wasiliysoft.tiqiaa_usb_demo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting

private const val LOG_TAG = "UsbIrService"

const val ACTION_USB_PERMISSION = "ru.wasiliysoft.tiqiaa.USB_PERMISSION"

fun interface IIrService {
    fun transmit(freq: Int, pattern: IntArray)
}

class UsbIrService private constructor(
    private val mConnection: UsbDeviceConnection,
    private val epOUT: UsbEndpoint,
    private val epIN: UsbEndpoint,
) : IIrService {
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

        fun getInstance(usbManager: UsbManager, device: UsbDevice): IIrService? {
            if (INSTANCE == null && isCompatibleDevice(device)) {
                var endpointIN: UsbEndpoint? = null
                var endpointOUT: UsbEndpoint? = null
                val usbInterface = device.getInterface(0)

                for (i in 0 until usbInterface.endpointCount) {
                    usbInterface.getEndpoint(i).let { endpoint ->
                        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            when (endpoint.direction) {
                                UsbConstants.USB_DIR_IN -> endpointIN = endpoint
                                UsbConstants.USB_DIR_OUT -> endpointOUT = endpoint
                                else -> Log.e(LOG_TAG, "undefined endpoints direction")
                            }
                        }
                    }
                }

                if (endpointIN == null || endpointOUT == null) {
                    Log.e(LOG_TAG, "Failed setting endpoint")
                    INSTANCE = null
                    return INSTANCE
                }

                val connection = usbManager.openDevice(device)
                if (connection == null || !connection.claimInterface(usbInterface, true)) {
                    Log.e(LOG_TAG, "open device FAIL!")
                    INSTANCE = null
                    return INSTANCE
                }
                Log.d(LOG_TAG, "open device SUCCESS!")
                INSTANCE = UsbIrService(connection, endpointOUT!!, endpointIN!!)
            }
            return INSTANCE
        }
    }

    override fun transmit(freq: Int, pattern: IntArray) {
        val toBulk = mutableListOf<List<Byte>>()

        // Settings blaster state Start
        val toSendState: List<Byte> =
            listOf(2, 9, getUsbPackId(), 1, 1, 83, 84, getCmdId(), 83, 69, 78)
        toBulk.add(toSendState)

        val tqIrWriteData = consumeIrToByteCode(pattern).apply {
            add(69)
            add(78)
        }.chunked(49)


        val cmdUsbPackId = getUsbPackId()
        val fragmentCount = tqIrWriteData.size.toByte()
        var f: List<Byte>
        tqIrWriteData.forEachIndexed { index, bytes ->
            f = if (index == 0) listOf(
                2,                              // buf[0] const. ReportId = 2
                (10 + bytes.size).toByte(),     // buf[1] packet size
                cmdUsbPackId,                   // buf[2]
                fragmentCount,                  // buf[3]
                1,                              // buf[4] fragment Cnt
                83,                             // const. S
                84,                             // const. T
                getCmdId(),                     // cmdId
                68,                             // const. D
                0                               // freq - not worked
            )
            else listOf(
                2,                              // buf[0] const. ReportId = 2
                (10 + bytes.size).toByte(),     //  buf[1] packet size
                cmdUsbPackId,                   // buf[2]
                fragmentCount,                  // buf[3]
                (index + 1).toByte(),           // buf[4] fragment Cnt
            )
            f = f.plus(bytes)
            toBulk.add(f)
        }

        // Settings blaster state Idle
        val toIdleState: List<Byte> =
            listOf(2, 9, getUsbPackId(), 1, 1, 83, 84, getCmdId(), 76, 69, 78)
        toBulk.add(toIdleState)

        toBulk.forEach {
//            Log.d("bulkTransfer", it.toString())
            bulkTransfer(it)
        }
    }

    private fun bulkTransfer(data: List<Byte>) {
        val byteWrite = ByteArray(61)
        data.forEachIndexed { i, v -> byteWrite[i] = v }
        try {
            mConnection.bulkTransfer(epOUT, byteWrite, byteWrite.size, 100)
            val bytesRead = ByteArray(epIN.maxPacketSize)
            mConnection.bulkTransfer(epIN, bytesRead, bytesRead.size, 100)
        } catch (e: Exception) {
            Log.e(LOG_TAG, e.message.toString())
            e.printStackTrace()
        }
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

@VisibleForTesting
fun consumeIrToByteCode(consumeIrPattern: IntArray): ArrayList<Byte> {
    val usbTickPattern = consumeIrPattern.map { it / 16 }
    val result = ArrayList<Byte>(usbTickPattern.size)
    for ((index, tickCount) in usbTickPattern.withIndex()) {
        result.addAll(usbTickToUsbByteCode(tickCount, index % 2 == 0))
    }
    return result
}

@VisibleForTesting
fun usbTickToUsbByteCode(tickCount: Int, isOn: Boolean): List<Byte> {
    val maximumBlockSize = 127
    var i = tickCount
    val result = ArrayList<Int>()
    while (i > 0) {
        var sendBlockSize = i
        if (sendBlockSize > maximumBlockSize) {
            sendBlockSize = maximumBlockSize
        }
        i -= sendBlockSize
        if (isOn) {
            sendBlockSize = sendBlockSize or 128
        }
        result.add(sendBlockSize)
    }
    return result.map { it.toByte() }
}

fun isCompatibleDevice(device: UsbDevice): Boolean {
    if (device.interfaceCount != 1) return false
    if (device.getInterface(0).endpointCount == 0) return false
    if (device.vendorId == 4292 && device.productId == 33896) return true
    if (device.vendorId == 1118 && device.productId == 33896) return true
    return false
}