package ru.wasiliysoft.tiqiaa_usb_demo

interface IrBlaster {
    fun init(): Boolean
    fun deinit()
    fun sendIrSignal(frequency: Int, pattern: List<Int>): Boolean
    fun isAvailable(): Boolean
}