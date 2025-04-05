package ru.wasiliysoft.tiqiaa_usb_demo

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Log

class BuiltInIrBlaster(private val context: Context) : IrBlaster {
    private var irManager: ConsumerIrManager? = null

    override fun init(): Boolean {
        irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        return irManager != null
    }

    override fun deinit() {
        irManager = null
    }

    override fun sendIrSignal(frequency: Int, pattern: List<Int>): Boolean {
        try {
            irManager?.transmit(frequency, pattern.toIntArray()) ?: false
            return true
        } catch (e: Exception) {
            Log.e("BuiltInIrBlaster", "Error sending IR signal", e)
            return false
        }
    }

    override fun isAvailable(): Boolean {
        return irManager?.hasIrEmitter() ?: false
    }
}