package com.timgromeyer.tvkiller

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Log

class BuiltInIrBlaster(private val context: Context) : IrBlaster {
    private var irManager: ConsumerIrManager? = null

    override fun init(): Boolean {
        val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (manager?.hasIrEmitter() == true) {
            irManager = manager
            return true
        }
        irManager = null
        return false
    }

    override fun deinit() {
        irManager = null
    }

    override fun sendIrSignal(frequency: Int, pattern: IntArray): Boolean {
        if (irManager == null) {
            Log.e("BuiltInIrBlaster", "IR manager not initialized")
            return false
        }
        try {
            irManager!!.transmit(frequency, pattern)
            return true
        } catch (e: Exception) {
            Log.e("BuiltInIrBlaster", "Error sending IR signal", e)
            return false
        }
    }

    override fun isAvailable(): Boolean {
        return irManager?.hasIrEmitter() == true
    }

    override fun isReady(): Boolean {
        return irManager != null
    }
}