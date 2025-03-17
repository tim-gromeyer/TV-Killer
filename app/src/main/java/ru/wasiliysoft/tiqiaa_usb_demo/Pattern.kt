package ru.wasiliysoft.tiqiaa_usb_demo

data class IrPattern(
    val designation: String,
    val patterns: List<Pattern>,
    val mute: Pattern
)

data class Pattern(
    val frequency: Int,
    val pattern: IntArray,
    val comment: String? = null
)