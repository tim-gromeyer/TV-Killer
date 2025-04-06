package com.timgromeyer.tvkiller

data class IrPattern(
    val designation: String,
    val patterns: List<Pattern?>,
    val mute: Pattern?
)

data class Pattern(
    val frequency: Int,
    val pattern: IntArray,
    val comment: String? = null
)