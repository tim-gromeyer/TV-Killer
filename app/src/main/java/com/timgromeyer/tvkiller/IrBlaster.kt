package com.timgromeyer.tvkiller

interface IrBlaster {
    fun init(): Boolean
    fun deinit()
    fun sendIrSignal(frequency: Int, pattern: IntArray): Boolean
    fun isAvailable(): Boolean
    fun isReady(): Boolean // Added to check readiness
}