#!/usr/bin/env kotlin

@file:OptIn(ExperimentalUnsignedTypes::class)

import java.io.DataInput
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.math.roundToInt

val Int.B: Byte
    get() = toByte()

val Int.UB: UByte
    get() = toUByte()

val Int.S: Short
    get() = toShort()

val Int.US: UShort
    get() = toUShort()

val Int.U: UInt
    get() = toUInt()

fun <I> I.readNBytesFully(len: Int): ByteArray where I : InputStream, I : DataInput =
    ByteArray(len).apply { readFully(this) }

class JumpTarget(val offset: UInt) {
    override fun toString(): String = "label_$offset"
}

class ParameterDescriptor<T>(
    val name: String,
    val range: IntRange,
    val type: Class<T>,
    val validate: ((T) -> Unit)? = null
) {
    init {
        val rangeLength = range.last + 1 - range.first
        require(rangeLength > 0) { "rangeLength ($rangeLength) <= 0" }
        val typeLength = when (type) {
            Boolean::class.java, UByte::class.java -> 1
            Short::class.java, UShort::class.java -> 2
            JumpTarget::class.java -> 4
            ByteArray::class.java -> -1
            else -> error("Unknown parameter type (${type})")
        }
        if (typeLength != -1) {
            require(rangeLength == typeLength) {
                "rangeLength ($rangeLength) != typeLength ($typeLength)"
            }
        }
    }
}

class InstructionDescriptor(
    val name: String,
    val code: UByte,
    val length: UByte,
    vararg val parameters: ParameterDescriptor<*>,
    val stringNames: Pair<String, String>? = null
) {
    init {
        require(length >= 4.UB) { "length ($length) < 4" }
        val parameterNames = parameters.mapTo(mutableSetOf()) {
            it.name.takeIf { it != "_" } ?: Any()
        }
        require(parameterNames.size == parameters.size) {
            "Duplicate parameter name"
        }
        var lastParameterEndInclusive = -1
        for (parameter in parameters) {
            require(parameter.range.first > lastParameterEndInclusive) {
                "parameter.range.start (${parameter.range.first}) <= lastParameterEndInclusive (" +
                    "$lastParameterEndInclusive)"
            }
            lastParameterEndInclusive = parameter.range.last
        }
        require(lastParameterEndInclusive < length.toInt()) {
            "lastParameterEndInclusive ($lastParameterEndInclusive) >= length ($length)"
        }
        if (stringNames != null) {
            val stringLengthParameter = parameters.find { it.name == stringNames.first }
            requireNotNull(stringLengthParameter) { "stringLengthParameter == null" }
            require(stringLengthParameter.type == UByte::class.java) {
                "stringLengthParameter.type (${stringLengthParameter.type}) != UByte::class.java"
            }
        }
    }
}

fun unknownInstructionDescriptor(code: UByte, length: UByte): InstructionDescriptor =
    InstructionDescriptor(
        "0x%02X".format(code.toByte()), code, length,
        ParameterDescriptor("unknownBytes", 2 until length.toInt(), ByteArray::class.java)
    )

val instructionDescriptors = listOf(
    InstructionDescriptor(
        "showMessage", 0x00.UB, 0x04.UB,
        ParameterDescriptor("textLength", 3..3, UByte::class.java),
        stringNames = "textLength" to "text"
    ),
    InstructionDescriptor("exitScript", 0x01.UB, 0x04.UB),
    InstructionDescriptor(
        "setNextScript", 0x02.UB, 0x04.UB,
        ParameterDescriptor("fileNameLength", 3..3, UByte::class.java),
        stringNames = "fileNameLength" to "fileName"
    ),
    InstructionDescriptor(
        "defineVariable", 0x04.UB, 0x08.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java),
        ParameterDescriptor("value", 4..5, Short::class.java),
    ),
    InstructionDescriptor(
        "addToVariable", 0x05.UB, 0x08.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java),
        ParameterDescriptor("value", 4..5, Short::class.java),
        ParameterDescriptor("_", 6..7, Short::class.java)
    ),
    InstructionDescriptor(
        "jumpIfVariableEqualTo", 0x06.UB, 0x10.UB,
        ParameterDescriptor("index", 4..4, UByte::class.java),
        ParameterDescriptor("value", 8..9, Short::class.java),
        ParameterDescriptor("target", 12..15, JumpTarget::class.java)
    ),
    InstructionDescriptor(
        "jumpIfVariableGreaterThan", 0x08.UB, 0x10.UB,
        ParameterDescriptor("index", 4..4, UByte::class.java),
        ParameterDescriptor("value", 8..9, Short::class.java),
        ParameterDescriptor("target", 12..15, JumpTarget::class.java)
    ),
    InstructionDescriptor(
        "jumpIfVariableLessThan", 0x09.UB, 0x10.UB,
        ParameterDescriptor("index", 4..4, UByte::class.java),
        ParameterDescriptor("value", 8..9, Short::class.java),
        ParameterDescriptor("target", 12..15, JumpTarget::class.java)
    ),
    InstructionDescriptor(
        "setMessageIndex", 0x0C.UB, 0x08.UB,
        ParameterDescriptor("index", 4..5, UShort::class.java)
    ),
    InstructionDescriptor(
        "jump", 0x0D.UB, 0x08.UB,
        ParameterDescriptor("target", 4..7, JumpTarget::class.java)
    ),
    InstructionDescriptor(
        "wait", 0x0E.UB, 0x08.UB,
        ParameterDescriptor("duration", 4..5, UShort::class.java)
    ),
    InstructionDescriptor(
        "setBackground", 0x0F.UB, 0x04.UB,
        ParameterDescriptor("_", 2..2, UByte::class.java),
        ParameterDescriptor("fileNameLength", 3..3, UByte::class.java),
        stringNames = "fileNameLength" to "fileName"
    ),
    InstructionDescriptor(
        "setBackgroundAndClearForegroundsAndAvatar", 0x10.UB, 0x04.UB,
        ParameterDescriptor("_", 2..2, UByte::class.java),
        ParameterDescriptor("fileNameLength", 3..3, UByte::class.java),
        stringNames = "fileNameLength" to "fileName"
    ),
    InstructionDescriptor("clearForegroundsAndAvatar", 0x11.UB, 0x08.UB),
    InstructionDescriptor(
        "loadForeground", 0x12.UB, 0x04.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java),
        ParameterDescriptor("fileNameLength", 3..3, UByte::class.java),
        stringNames = "fileNameLength" to "fileName"
    ),
    InstructionDescriptor(
        "setForeground", 0x13.UB, 0x08.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java),
        ParameterDescriptor("scale", 3..3, UByte::class.java),
        ParameterDescriptor("centerX", 4..5, Short::class.java),
        ParameterDescriptor("top", 6..7, Short::class.java)
    ),
    InstructionDescriptor(
        "crossFadeAndClearForegroundsForAnimation", 0x14.UB, 0x08.UB,
        ParameterDescriptor("transitionDuration", 4..5, UShort::class.java)
    ),
    InstructionDescriptor(
        "setBackgroundColorAndClearForegroundsAndAvatar", 0x16.UB, 0x08.UB,
        ParameterDescriptor("_", 2..2, UByte::class.java),
        ParameterDescriptor("color", 4..6, ByteArray::class.java)
    ),
    InstructionDescriptor(
        "endAndShowChoices", 0x1B.UB, 0x04.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java)
    ),
    InstructionDescriptor("startChoices", 0x1C.UB, 0x04.UB),
    InstructionDescriptor(
        "addChoice", 0x1D.UB, 0x08.UB,
        ParameterDescriptor("textLength", 2..2, UByte::class.java),
        ParameterDescriptor("target", 4..7, JumpTarget::class.java),
        stringNames = "textLength" to "text"
    ),
    // Only appeared in Ete and Automne and only called for non-virtual ends.
    InstructionDescriptor(
        "setVisibleEndCompleted", 0x1E.UB, 0x04.UB,
        ParameterDescriptor("index", 3..3, UByte::class.java)
    ),
    // Appeared in all four seasons and called for all ends including the two virtual ends in
    // 02a_00001.s and 03a_00001.s. Used by jumpIfEndCompleted() in Printemps to Automne,
    // although for Printemps it's probably only counting the good ends.
    InstructionDescriptor(
        "setEndCompleted", 0x21.UB, 0x04.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java)
    ),
    InstructionDescriptor(
        "playMusic", 0x22.UB, 0x08.UB,
        ParameterDescriptor("_", 2..2, UByte::class.java),
        ParameterDescriptor("loop", 3..3, Boolean::class.java),
        ParameterDescriptor("fileNameLength", 7..7, UByte::class.java),
        stringNames = "fileNameLength" to "fileName"
    ),
    InstructionDescriptor("stopMusic", 0x23.UB, 0x04.UB),
    InstructionDescriptor(
        "fadeOutMusic", 0x24.UB, 0x08.UB,
        ParameterDescriptor("duration", 4..5, UShort::class.java)
    ),
    InstructionDescriptor(
        "playMusicWithFadeIn", 0x25.UB, 0x0C.UB,
        ParameterDescriptor("_", 2..2, UByte::class.java),
        ParameterDescriptor("loop", 3..3, Boolean::class.java),
        ParameterDescriptor("duration", 4..5, UShort::class.java),
        ParameterDescriptor("fileNameLength", 8..8, UByte::class.java),
        stringNames = "fileNameLength" to "fileName"
    ),
    InstructionDescriptor(
        "playVoice", 0x27.UB, 0x08.UB,
        ParameterDescriptor("fileNameLength", 7..7, UByte::class.java),
        stringNames = "fileNameLength" to "fileName"
    ),
    InstructionDescriptor(
        "playSoundEffect", 0x28.UB, 0x08.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java),
        ParameterDescriptor("loop", 3..3, Boolean::class.java),
        ParameterDescriptor("fileNameLength", 7..7, UByte::class.java),
        stringNames = "fileNameLength" to "fileName"
    ),
    InstructionDescriptor(
        "stopSoundEffect", 0x29.UB, 0x04.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java)
    ),
    InstructionDescriptor(
        "stopVoice", 0x2A.UB, 0x04.UB,
        ParameterDescriptor("_", 2..2, UByte::class.java)
    ),
    InstructionDescriptor(
        "fadeOutSoundEffect", 0x2C.UB, 0x08.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java),
        ParameterDescriptor("_", 3..3, UByte::class.java),
        ParameterDescriptor("duration", 4..5, UShort::class.java)
    ),
    InstructionDescriptor(
        "playSoundEffectWithFadeIn", 0x2D.UB, 0x0C.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java),
        ParameterDescriptor("loop", 3..3, Boolean::class.java),
        ParameterDescriptor("duration", 4..5, UShort::class.java),
        ParameterDescriptor("fileNameLength", 8..8, UByte::class.java),
        stringNames = "fileNameLength" to "fileName"
    ),
    InstructionDescriptor(
        "showYuriChange", 0x35.UB, 0x04.UB,
        ParameterDescriptor("type", 3..3, UByte::class.java) {
            // 1: Down
            // 2: Up
            require(it in ubyteArrayOf(1.UB, 2.UB)) { "type ($it) ! in arrayOf(1, 2)" }
        }
    ),
    // Only appeared in 01a_02600.s and 04a_04900.s, and doesn't seem to do anything interesting.
    InstructionDescriptor(
        "_", 0x36.UB, 0x04.UB,
        ParameterDescriptor("_", 3..3, UByte::class.java) { require(it == 1.UB) { "_ ($it) != 1" } }
    ),
    // Only appeared in Automne and Hiver and only called for normal or good ends. Used by
    // jumpIfEndCompleted() in Hiver.
    InstructionDescriptor(
        "setGoodEndCompleted", 0x3A.UB, 0x04.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java)
    ),
    InstructionDescriptor(
        "jumpIfEndCompleted", 0x3B.UB, 0x08.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java),
        ParameterDescriptor("target", 4..7, JumpTarget::class.java)
    ),
    InstructionDescriptor(
        "addBacklog", 0x3F.UB, 0x04.UB,
        ParameterDescriptor("textLength", 3..3, UByte::class.java),
        stringNames = "textLength" to "text"
    ),
    InstructionDescriptor(
        "setWindowVisible", 0x40.UB, 0x04.UB,
        ParameterDescriptor("visible", 2..2, Boolean::class.java)
    ),
    InstructionDescriptor("clearVerticalMessages", 0x4C.UB, 0x04.UB),
    InstructionDescriptor(
        "fadeWindow", 0x4D.UB, 0x08.UB,
        ParameterDescriptor("visible", 3..3, Boolean::class.java),
        ParameterDescriptor("duration", 4..5, UShort::class.java)
    ),
    InstructionDescriptor(
        "playSpecialEffect", 0x50.UB, 0x0C.UB,
        // The only type of special effect played was shaking the screen diagonally, to bottom-left,
        // then top-right, then back.
        // - 00_01_10: See https://www.bilibili.com/video/BV1dv411B7Z4?p=3&t=1914
        // - 05_01_0C: See https://www.bilibili.com/video/BV1x54y1Q7dd?p=4&t=139
        // - 05_00_06: See https://www.bilibili.com/video/BV1XW4y1B7sn?t=372
        ParameterDescriptor("_", 2..2, ByteArray::class.java),
        ParameterDescriptor("additionalCount", 3..3, UByte::class.java),
        ParameterDescriptor("distance", 4..4, UByte::class.java),
        ParameterDescriptor("duration", 8..9, UShort::class.java)
    ),
    InstructionDescriptor("stopSpecialEffect", 0x51.UB, 0x05.UB),
    InstructionDescriptor(
        "waitForClick", 0x54.UB, 0x04.UB,
        ParameterDescriptor("type", 3..3, UByte::class.java) {
            // 0: Invisible click wait
            // 1: Message click wait, seems identical to 2 and only appeared once in 04a_06400.s,
            //    though the window is invisible at that time
            // 2: Message click wait
            require(it in ubyteArrayOf(0.UB, 1.UB, 2.UB)) { "type ($it) !in arrayOf(0, 1, 2)" }
        }
    ),
    // Only appeared in Ete - Hiver and called for non-bad ends. The parameter is always 0
    // for Ete and Automne, but 1 for Good End and 2 for Grand Finale in Hiver (otherwise not
    // called).
    // TODO: Could be related to game start animation?
    InstructionDescriptor(
        "0x57", 0x57.UB, 0x04.UB,
        ParameterDescriptor("unknownByte", 2..2, UByte::class.java)
    ),
    // Only used in 04a_02700s.s. TODO: Likely related to selection?
    unknownInstructionDescriptor(0x5D.UB, 0x04.UB),
    unknownInstructionDescriptor(0x5E.UB, 0x04.UB),
    unknownInstructionDescriptor(0x5F.UB, 0x08.UB),
    unknownInstructionDescriptor(0x60.UB, 0x54.UB),
    unknownInstructionDescriptor(0x61.UB, 0x04.UB),
    InstructionDescriptor(
        "setForegroundAnimationStart", 0x72.UB, 0x14.UB,
        ParameterDescriptor("_", 2..2, UByte::class.java),
        ParameterDescriptor("index", 3..3, UByte::class.java),
        ParameterDescriptor("centerX", 4..5, Short::class.java),
        ParameterDescriptor("top", 6..7, Short::class.java),
        ParameterDescriptor("scaleX", 8..8, UByte::class.java) {
            require(it in 0.UB..100.UB) { "scaleX ($it) !in 0..100" }
        },
        ParameterDescriptor("scaleY", 10..10, UByte::class.java) {
            require(it in 0.UB..100.UB) { "scaleY ($it) !in 0..100" }
        },
        ParameterDescriptor("alpha", 12..12, UByte::class.java),
        ParameterDescriptor("loop", 14..14, Boolean::class.java),
        ParameterDescriptor("_", 16..17, UShort::class.java),
        ParameterDescriptor("_", 18..19, UShort::class.java)
    ),
    InstructionDescriptor(
        "setForegroundAnimationEnd", 0x73.UB, 0x14.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java),
        ParameterDescriptor("_", 3..3, UByte::class.java),
        ParameterDescriptor("centerX", 4..5, Short::class.java),
        ParameterDescriptor("top", 6..7, Short::class.java),
        ParameterDescriptor("scaleX", 8..8, UByte::class.java) {
            require(it in 0.UB..100.UB) { "scaleX ($it) !in 0..100" }
        },
        ParameterDescriptor("scaleY", 10..10, UByte::class.java) {
            require(it in 0.UB..100.UB) { "scaleY ($it) !in 0..100" }
        },
        ParameterDescriptor("alpha", 12..12, UByte::class.java),
        ParameterDescriptor("duration", 16..17, UShort::class.java)
    ),
    InstructionDescriptor("playAllForegroundAnimations", 0x74.UB, 0x04.UB),
    InstructionDescriptor(
        "stopForegroundAnimation", 0x75.UB, 0x04.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java)
    ),
    // Only appeared in Hiver.
    InstructionDescriptor(
        "0x83", 0x83.UB, 0x08.UB,
        ParameterDescriptor("unknownByte", 3..3, UByte::class.java),
        ParameterDescriptor("unknownShort", 4..5, UShort::class.java),
    ),
    // Only appeared in 04a_02700s.s. TODO: Likely return to selection?
    InstructionDescriptor("0x8B", 0x8B.UB, 0x04.UB),
    InstructionDescriptor(
        "loadForegroundForAnimation", 0x9C.UB, 0x04.UB,
        ParameterDescriptor("index", 2..2, UByte::class.java),
        ParameterDescriptor("fileNameLength", 3..3, UByte::class.java),
        stringNames = "fileNameLength" to "fileName"
    ),
    InstructionDescriptor(
        "playVideo", 0xB2.UB, 0x08.UB,
        ParameterDescriptor("index", 4..4, UByte::class.java) {
            // 0: OP (OP1 or OP2 is selected automatically for Hiver)
            // 1: Grand Finale ED (Hiver)
            require(it in ubyteArrayOf(0.UB, 1.UB)) { "index ($it) !in arrayOf(0, 1)" }
        }
    ),
    InstructionDescriptor(
        "playCredits", 0xB3.UB, 0x04.UB,
        ParameterDescriptor("type", 3..3, UByte::class.java) {
            // 1: True end (Printemps, Ete, Automne) / Good end (Hiver) credits
            // 3: Normal end credits
            require(it in ubyteArrayOf(1.UB, 3.UB)) { "type ($it) !in arrayOf(1, 3)" }
        }
    ),
    InstructionDescriptor(
        "setAvatar", 0xB4.UB, 0x04.UB,
        ParameterDescriptor("_", 2..2, UByte::class.java),
        ParameterDescriptor("fileNameLength", 3..3, UByte::class.java),
        stringNames = "fileNameLength" to "fileName"
    ),
    InstructionDescriptor(
        "setWindowStyle", 0xB6.UB, 0x04.UB,
        ParameterDescriptor("style", 2..2, UByte::class.java) {
            // 0: Normal
            // 1: Vertical
            // 2: Transparent
            require(it in ubyteArrayOf(0.UB, 1.UB, 2.UB)) {
                "style ($it) !in arrayOf(0, 1, 2)"
            }
        }
    ),
    InstructionDescriptor(
        "setChapter", 0xB8.UB, 0x04.UB,
        ParameterDescriptor("index", 3..3, UByte::class.java)
    ),
    // Always called before an end is set as completed, but doesn't seem to do anything interesting.
    InstructionDescriptor("0xBA", 0xBA.UB, 0x04.UB),
    InstructionDescriptor(
        "decreaseMusicVolume", 0xBB.UB, 0x08.UB,
        ParameterDescriptor("volume", 3..3, UByte::class.java) {
            require(it in 0.UB..100.UB) { "volume ($it) !in 0..100" }
        },
        ParameterDescriptor("duration", 4..5, UShort::class.java)
    ),
    InstructionDescriptor(
        "increaseMusicVolume", 0xBC.UB, 0x08.UB,
        ParameterDescriptor("volume", 2..2, UByte::class.java) {
            require(it in 0.UB..100.UB) { "volume ($it) !in 0..100" }
        },
        ParameterDescriptor("duration", 4..5, UShort::class.java)
    ),
    InstructionDescriptor(
        "decreaseAllSoundEffectsVolume", 0xBD.UB, 0x08.UB,
        ParameterDescriptor("volume", 3..3, UByte::class.java) {
            require(it in 0.UB..100.UB) { "volume ($it) !in 0..100" }
        },
        ParameterDescriptor("duration", 4..5, UShort::class.java)
    ),
    InstructionDescriptor(
        "increaseAllSoundEffectsVolume", 0xBE.UB, 0x08.UB,
        ParameterDescriptor("volume", 3..3, UByte::class.java) {
            require(it in 0.UB..100.UB) { "volume ($it) !in 0..100" }
        },
        ParameterDescriptor("duration", 4..5, UShort::class.java)
    ),
    InstructionDescriptor(
        "playForegroundAnimations", 0xBF.UB, 0x10.UB,
        ParameterDescriptor("count", 2..2, UByte::class.java),
        ParameterDescriptor("indices", 3..15, ByteArray::class.java)
    ),
    InstructionDescriptor(
        "stopForegroundAnimations", 0xC0.UB, 0x10.UB,
        ParameterDescriptor("count", 2..2, UByte::class.java),
        ParameterDescriptor("indices", 3..15, ByteArray::class.java)
    )
)

class Instruction(
    val descriptor: InstructionDescriptor,
    val parameters: LinkedHashMap<String, *>,
    val bytes: ByteArray
) {
    override fun toString(): String =
        "${descriptor.name} (" + parameters.filter { it.key != descriptor.stringNames?.first }
            .map { "${it.key} = ${parameterValueToString(it.value)}" }.joinToString() + ")"

    private fun parameterValueToString(value: Any?): String = when (value) {
        is Boolean -> if (value) "1" else "0"
        is UByte, is Short, is UShort, is JumpTarget -> value.toString()
        is ByteArray -> value.joinToString("_", "0x") { "%02X".format(it) }
        is String -> "\"$value\""
        else -> error("Unknown parameter value ($value)")
    }
}

val instructionCodeToDescriptor = instructionDescriptors.associateBy { it.code }
    .also { require(it.size == instructionDescriptors.size) { "Duplicate instruction code" } }

private val GBK_SPECIAL_CHARS = mapOf(
    '仈' to '＃',
    '亹' to '＄',
    // Since Autumn
    '傽' to '）',
    '偂' to '，',
    '傿' to '！',
    '偅' to '。',
    '僁' to '？',
    '偉' to '、',
    '僃' to '’',
    '偋' to '」',
    '僅' to '”',
    '偭' to '》',
)

fun <I> parseInstruction(
    inputStream: I,
    charset: Charset
): Instruction? where I : InputStream, I : DataInput {
    val code = inputStream.read().takeIf { it != -1 }?.toUByte() ?: return null
    val length = inputStream.readByte().toUByte()
    val descriptor = requireNotNull(instructionCodeToDescriptor[code]) {
        "Unknown instruction code ($code)"
    }
    require(length == descriptor.length) {
        "length ($length) != descriptor.length (${descriptor.length})"
    }
    val fixedBytes = byteArrayOf(code.toByte(), length.toByte()) +
        inputStream.readNBytesFully(length.toInt() - 2)
    val parameters = LinkedHashMap<String, Any?>()
    for (parameterDescriptor in descriptor.parameters) {
        val parameterBytes = fixedBytes.copyOfRange(
            parameterDescriptor.range.first, parameterDescriptor.range.last + 1
        )
        val parameterValue: Any = when (parameterDescriptor.type) {
            Boolean::class.java -> parameterBytes[0] != 0.B
            UByte::class.java -> parameterBytes[0].toUByte()
            Short::class.java ->
                ByteBuffer.wrap(parameterBytes).order(ByteOrder.LITTLE_ENDIAN).short
            UShort::class.java ->
                ByteBuffer.wrap(parameterBytes).order(ByteOrder.LITTLE_ENDIAN).short.toUShort()
            JumpTarget::class.java -> JumpTarget(
                ByteBuffer.wrap(parameterBytes).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
            )
            ByteArray::class.java -> parameterBytes
            else -> error("Unknown parameter type (${parameterDescriptor.type})")
        }
        @Suppress("UNCHECKED_CAST")
        (parameterDescriptor.validate as ((Any?) -> Unit)?)?.invoke(parameterValue)
        if (parameterDescriptor.name != "_") {
            parameters[parameterDescriptor.name] = parameterValue
        }
    }
    var lastParameterEnd = 2
    for (parameterDescriptor in descriptor.parameters) {
        val zeroParameterBytes = fixedBytes.copyOfRange(
            lastParameterEnd, parameterDescriptor.range.first
        )
        require(zeroParameterBytes.all { it == 0.B }) {
            "!zeroParameterBytes.all { it == 0 }"
        }
        lastParameterEnd = parameterDescriptor.range.last + 1
    }
    val zeroParameterBytes = fixedBytes.copyOfRange(lastParameterEnd, length.toInt())
    require(zeroParameterBytes.all { it == 0.B }) { "!zeroParameterBytes.all { it == 0 }" }
    val stringBytes = if (descriptor.stringNames != null) {
        val (stringLengthName, stringName) = descriptor.stringNames
        val stringLength = parameters[stringLengthName] as UByte
        val stringBytes = inputStream.readNBytesFully(stringLength.toInt())
        val stringActualLength = stringBytes.indexOf(0).takeIf { it != -1 } ?: stringBytes.size
        require(stringLength.toInt() - stringActualLength <= 4) {
            "stringLength ($stringLength) - stringActualLength ($stringActualLength) > 4"
        }
        // - ＃: Speaker name
        // - <>: Hiragana
        // - ＄: New line
        val stringActualValue =
            String(stringBytes, 0, stringActualLength, charset).let {
                if (charset == GBK) {
                    it.map { char -> GBK_SPECIAL_CHARS.getOrElse(char) { char } }.joinToString("")
                } else {
                    it
                }
            }.let {
                if (charset == GBK && stringName == "text") {
                    it.filter { char -> char != '_' }
                } else {
                    it
                }
            }
        parameters[stringName] = stringActualValue
        stringBytes
    } else {
        null
    }
    val bytes = if (stringBytes != null) fixedBytes + stringBytes else fixedBytes
    return Instruction(descriptor, parameters, bytes)
}

class Comment(val value: String) {
    override fun toString(): String = "#$value"
}

sealed interface Value {
    companion object
}

fun Value.Companion.from(value: String): Value =
    if (isValidLiteralValue(value)) LiteralValue(value) else QuotedValue(value)

class LiteralValue(val value: String) : Value {
    init {
        require(isValidLiteralValue(value)) { "Invalid literal value \"$value\"" }
    }

    override fun toString(): String = value
}

private fun isValidLiteralValue(value: String): Boolean {
    if (value.isEmpty()) {
        return false
    }
    for (char in "\r\t ") {
        if (value.startsWith(char)) {
            return false
        }
        if (value.endsWith(char)) {
            return false
        }
    }
    for (char in "\n#;:,=\"`") {
        if (char in value) {
            return false
        }
    }
    return true
}

class QuotedValue(val value: String) : Value {
    override fun toString(): String = "\"${escapeValue(value, '"')}\""
}

class ScriptValue(val value: String) : Value {
    override fun toString(): String = "`${escapeValue(value, '`')}`"
}

private fun escapeValue(value: String, quotationChar: Char): String = buildString {
    for (char in value) {
        when (char) {
            '\t' -> append("\\t")
            '\r' -> append("\\r")
            '\n' -> append("\\n")
            '\\' -> append("\\\\")
            quotationChar -> {
                append('\\')
                append(quotationChar)
            }
            else -> append(char)
        }
    }
}

class Property(
    val name: Value?,
    val value: Value
) {
    override fun toString(): String = if (name != null) "$name = $value" else value.toString()

    companion object
}

fun Property.Companion.from(name: String?, value: String): Property =
    Property(name?.let { Value.from(it) }, Value.from(value))

sealed interface Line {
    val comment: Comment?
}

class BlankLine : Line {
    override val comment: Comment? = null

    override fun toString(): String = ""
}

class CommentLine(override val comment: Comment) : Line {
    override fun toString(): String = comment.toString()
}

fun CommentLine(comment: String) : CommentLine = CommentLine(Comment(comment))

class CommandLine(
    val name: Value,
    val arguments: List<Value>,
    override val comment: Comment?
) : Line {
    override fun toString(): String =
        ": $name${if (arguments.isNotEmpty()) " ${arguments.joinToString()}" else ""}" +
            comment.toStringWithPrecedingSpaceOrEmpty()
}

fun CommandLine(
    name: String,
    vararg arguments: String,
    comment: String? = null
): CommandLine =
    CommandLine(Value.from(name), arguments.map { Value.from(it) }, comment?.let { Comment(it) })

class ElementLine(
    val name: Value,
    val properties: List<Property>,
    override val comment: Comment?
) : Line {
    init {
        require(properties.isNotEmpty()) { "Invalid element line for \"$name\" without properties" }
        for ((index, property) in properties.withIndex()) {
            if (index == 0) {
                continue
            }
            require(property.name != null) {
                "Invalid property \"${property.value}\" without name in element line for \"$name\""
            }
        }
    }

    override fun toString(): String =
        "$name: ${properties.joinToString()}${comment.toStringWithPrecedingSpaceOrEmpty()}"
}

fun ElementLine(
    name: String,
    value: String?,
    vararg properties: Pair<String?, String>,
    comment: String? = null
): ElementLine =
    ElementLine(
        Value.from(name),
        (value?.let { listOf(Property.from(null, value)) } ?: emptyList()) +
            properties.map { (name, value) -> Property.from(name, value) },
        comment?.let { Comment(it) }
    )

fun ElementLine(
    name: String,
    vararg properties: Pair<String?, String>,
    comment: String? = null
): ElementLine = ElementLine(name, null, *properties, comment = comment)

class BatchedElementsLine(
    val batchedProperties: List<List<Property>>,
    override val comment: Comment?
) : Line {
    init {
        for (properties in batchedProperties) {
            require(properties.isNotEmpty()) {
                "Invalid batched element line without properties"
            }
            for ((index, property) in properties.withIndex()) {
                if (index == 0) {
                    continue
                }
                require(property.name != null) {
                    "Invalid property \"${property.value}\" without name in batched element line"
                }
            }
        }
    }

    override fun toString(): String =
        batchedProperties.joinToString("; ") { it.joinToString() } +
            comment.toStringWithPrecedingSpaceOrEmpty()
}

fun BatchedElementsLine(
    vararg batchedProperties: List<Pair<String?, String>>,
    comment: String? = null
): BatchedElementsLine =
    BatchedElementsLine(
        batchedProperties.map { properties ->
            properties.map { (name, value) -> Property.from(name, value) }
        },
        comment?.let { Comment(it) }
    )

private fun Comment?.toStringWithPrecedingSpaceOrEmpty(): String =
    if (this != null) " $this" else ""

enum class WindowStyle(
    val layout: String,
) {
    DIALOGUE("dialogue"),
    MONOLOGUE("monologue"),
    NARRATION("narration"),
}

class VnmarkConversionState {
    // FIXME: These may break after jumps.
    var foregroundForAnimationIndices = mutableSetOf<Int>()
    var windowStyle = WindowStyle.DIALOGUE
    var monologueTextIndex = 0
    var pendingAnimationIndexToStartProperties = mutableMapOf<Int, List<Pair<String, String>>>()
    var pendingClearMessage = false
    var pendingClearMessageSkipName = false
    val pendingChoiceJumpTargets = mutableListOf<JumpTarget>()
}

fun Instruction.toVnMarkLines(state: VnmarkConversionState): List<Line> {
    val clearMessageIfPendingLines = if (state.pendingClearMessage) {
        if (state.pendingClearMessageSkipName) {
            listOf(
                ElementLine("text", "none"),
                CommandLine("snap", "text"),
            )
        } else {
            listOf(
                ElementLine("name", "none"),
                ElementLine("text", "none"),
                CommandLine("snap", "name", "text"),
            )
        }
    } else {
        emptyList()
    }
    val lineOrLines: Any? = when (descriptor.name) {
        "showMessage" -> {
            val text = getParameter<String>("text")
            if (state.windowStyle == WindowStyle.MONOLOGUE) {
                ++state.monologueTextIndex
                listOf(ElementLine("text${state.monologueTextIndex}", text), BlankLine())
            } else {
                if (text.startsWith('＃')) {
                    state.pendingClearMessageSkipName = true
                    ElementLine("name", text.substring(1))
                } else {
                    state.pendingClearMessage = true
                    state.pendingClearMessageSkipName = false
                    clearMessageIfPendingLines + listOf(ElementLine("text", text), BlankLine())
                }
            }
        }
        "exitScript" -> CommandLine("exit")
        "setNextScript" -> CommandLine("exec", getParameter("fileName"))
        "defineVariable" ->
            CommandLine(
                "eval",
                "$['${getParameter<UByte>("index")}'] = ${getParameter<Short>("value")}"
            )
        "addToVariable" ->
            CommandLine(
                "eval",
                "$['${getParameter<UByte>("index")}'] += ${getParameter<Short>("value")}"
            )
        "jumpIfVariableEqualTo" ->
            CommandLine(
                "jump_if",
                getParameter<JumpTarget>("target").toString(),
                "$['${getParameter<UByte>("index")}'] === ${getParameter<Short>("value")}"
            )
        "jumpIfVariableGreaterThan" ->
            CommandLine(
                "jump_if",
                getParameter<JumpTarget>("target").toString(),
                "$['${getParameter<UByte>("index")}'] > ${getParameter<Short>("value")}"
            )
        "jumpIfVariableLessThan" ->
            CommandLine(
                "jump_if",
                getParameter<JumpTarget>("target").toString(),
                "$['${getParameter<UByte>("index")}'] < ${getParameter<Short>("value")}"
            )
        "setMessageIndex" -> CommentLine(toString())
        "jump" -> CommandLine("jump", getParameter<JumpTarget>("target").toString())
        "wait" -> CommandLine("delay", getParameter<UShort>("duration").toString())
        "setBackground" -> ElementLine("background", value = getParameter("fileName"))
        "setBackgroundAndClearForegroundsAndAvatar" -> {
            state.foregroundForAnimationIndices.clear()
            listOf(
                ElementLine("background", value = getParameter("fileName")),
                ElementLine("foreground*", "none", "alpha" to "initial"),
                ElementLine("avatar", "none"),
            )
        }
        "clearForegroundsAndAvatar" -> {
            state.foregroundForAnimationIndices.clear()
            listOf(
                ElementLine("foreground*", "none", "alpha" to "initial"),
                ElementLine("avatar", "none"),
            )
        }
        "loadForeground" -> {
            val foregroundElementIndex = getParameter<UByte>("index").toInt() + 1
            state.foregroundForAnimationIndices -= foregroundElementIndex
            val foregroundElementName = "foreground$foregroundElementIndex"
            ElementLine(foregroundElementName, value = getParameter("fileName"))
        }
        "setForeground" -> {
            val foregroundElementName = "foreground${getParameter<UByte>("index").toInt() + 1}"
            val scale = "${getParameter<UByte>("scale")}%"
            ElementLine(
                foregroundElementName,
                "pivot_y" to "0",
                "scale_x" to scale,
                "scale_y" to scale,
                "anchor_x" to "50%",
                "position_x" to "${getParameter<Short>("centerX")}px",
                "position_y" to "${getParameter<Short>("top")}px"
            )
        }
        "crossFadeAndClearForegroundsForAnimation" -> {
            val duration = getParameter<UShort>("transitionDuration").toInt()
            val clearForegroundsForAnimationLines =
                state.foregroundForAnimationIndices.map { ElementLine("foreground$it", "none") }
            state.foregroundForAnimationIndices.clear()
            state.pendingClearMessage = false
            state.pendingClearMessageSkipName = false
            if (duration > 1) {
                listOf(
                    ElementLine(
                        "effect",
                        "cross-fade",
                        "parameters" to duration.toString(),
                    ),
                ) + clearForegroundsForAnimationLines + clearMessageIfPendingLines + listOf(
                    CommandLine("snap", "background", "foreground*", "avatar"),
                    CommandLine("wait", "effect"),
                    ElementLine("effect", "none"),
                    CommandLine("snap", "effect"),
                )
            } else {
                clearForegroundsForAnimationLines + clearMessageIfPendingLines +
                    CommandLine("snap", "background", "foreground*", "avatar")
            }
        }
        "setBackgroundColorAndClearForegroundsAndAvatar" -> {
            state.foregroundForAnimationIndices.clear()
            @OptIn(ExperimentalStdlibApi::class)
            val color = getParameter<ByteArray>("color").toHexString(HexFormat.UpperCase)
            val background =
                when (color) {
                    "000000" -> "black"
                    "FFFFFF" -> "white"
                    else -> error("Unsupported color $color")
                }
            listOf(
                ElementLine("background", background),
                ElementLine("foreground*", "none", "alpha" to "initial"),
                ElementLine("avatar", "none"),
            )
        }
        "endAndShowChoices" -> {
            val choiceJumpTargets = state.pendingChoiceJumpTargets.toList()
            state.pendingChoiceJumpTargets.clear()
            check(choiceJumpTargets.isNotEmpty())
            state.pendingClearMessage = false
            state.pendingClearMessageSkipName = false
            listOf(
                CommandLine("wait", "choice*"),
                CommandLine("pause"),
                ElementLine("choice*", "none"),
                CommandLine("wait", "choice*"),
            ) + clearMessageIfPendingLines +
                choiceJumpTargets.mapIndexed { index, jumpTarget ->
                    CommandLine("jump_if", jumpTarget.toString(), "$['choice'] === ${index + 1}")
                }
        }
        "startChoices" -> {
            check(state.pendingChoiceJumpTargets.isEmpty())
            null
        }
        "addChoice" -> {
            val choiceElementName = "choice${state.pendingChoiceJumpTargets.size + 1}"
            val script = "$['choice'] = ${state.pendingChoiceJumpTargets.size + 1}"
            state.pendingChoiceJumpTargets += getParameter<JumpTarget>("target")
            ElementLine(choiceElementName, value = getParameter("text"), "script" to script)
        }
        "setVisibleEndCompleted" -> CommentLine(toString())
        "setEndCompleted" -> CommandLine(
            "eval",
            "$['end_${getParameter<UByte>("index")}'] = true"
        )
        "playMusic" ->
            ElementLine(
                "music",
                value = getParameter("fileName"),
                "loop" to getParameter<Boolean>("loop").toString(),
                "transition_duration" to "0",
            )
        "stopMusic" -> ElementLine("music", "none", "transition_duration" to "0")
        "fadeOutMusic" ->
            ElementLine(
                "music",
                "none",
                "transition_duration" to "${getParameter<UShort>("duration")}ms"
            )
        "playMusicWithFadeIn" ->
            ElementLine(
                "music",
                value = getParameter("fileName"),
                "loop" to getParameter<Boolean>("loop").toString(),
                "transition_duration" to "${getParameter<UShort>("duration")}ms"
            )
        "playVoice" -> ElementLine("voice", value = getParameter("fileName"))
        "playSoundEffect" -> {
            val soundElementName = "sound${getParameter<UByte>("index").toInt() + 1}"
            listOf(
                ElementLine(soundElementName, "none", "transition_duration" to "0"),
                CommandLine("snap", soundElementName),
                ElementLine(
                    soundElementName,
                    value = getParameter("fileName"),
                    "loop" to getParameter<Boolean>("loop").toString(),
                    "transition_duration" to "0",
                )
            )
        }
        "stopSoundEffect" -> {
            val soundElementName = "sound${getParameter<UByte>("index").toInt() + 1}"
            ElementLine(soundElementName, "none", "transition_duration" to "0")
        }
        "stopVoice" -> ElementLine("voice", "none")
        "fadeOutSoundEffect" -> {
            val soundElementName = "sound${getParameter<UByte>("index").toInt() + 1}"
            ElementLine(
                soundElementName,
                "volume" to "0%",
                "transition_duration" to "${getParameter<UShort>("duration")}ms"
            )
        }
        "playSoundEffectWithFadeIn" -> {
            val soundElementName = "sound${getParameter<UByte>("index").toInt() + 1}"
            ElementLine(
                soundElementName,
                value = getParameter("fileName"),
                "loop" to getParameter<Boolean>("loop").toString(),
                "transition_duration" to "${getParameter<UShort>("duration")}ms"
            )
        }
        "showYuriChange" -> {
            val isUp = getParameter<UByte>("type") == 2.UB
            CommandLine(
                "_animate",
                if (isUp) "yuri-up" else "yuri-down",
                "[{ \"opacity\": [0, 1, 0] }, 1000]"
            )
        }
        "setGoodEndCompleted" -> CommentLine(toString())
        "jumpIfEndCompleted" -> CommandLine(
            "jump_if",
            getParameter<JumpTarget>("target").toString(),
            "$['end_${getParameter<UByte>("index")}']"
        )
        "addBacklog" -> ElementLine("text", value = getParameter("text"), comment = toString())
        "setWindowVisible" ->
            CommandLine(
                "set_layout",
                if (getParameter("visible")) state.windowStyle.layout else "none",
            )
        "clearVerticalMessages" -> {
            state.monologueTextIndex = 0
            listOf(ElementLine("text*", "none"), CommandLine("snap", "text*"))
        }
        "fadeWindow" ->
            CommandLine(
                "set_layout",
                if (getParameter("visible")) state.windowStyle.layout else "none",
                // "duration" is ignored here.
                comment = toString()
            )
        "playSpecialEffect" ->
            ElementLine(
                "effect",
                "animate",
                "parameters" to "{ \"translate\": [\"0 0\", \"-11px 11px\", \"0 0\", \"11px -11px\", \"0 0\", \"-11px 11px\", \"0 0\", \"11px -11px\", \"0 0\", \"-11px 11px\", \"0 0\"] }, 300"
            )
        "stopSpecialEffect" -> listOf(ElementLine("effect", "none"), CommandLine("snap", "effect"))
        "waitForClick" -> CommandLine("pause", comment = toString())
        "setForegroundAnimationStart" -> {
            val elementIndex = getParameter<UByte>("index").toInt() + 1;
            val properties = listOf(
                "anchor_x" to "50%",
                "position_x" to "${getParameter<Short>("centerX")}px",
                "position_y" to "${getParameter<Short>("top")}px",
                "pivot_y" to "0",
                "scale_x" to "${getParameter<UByte>("scaleX")}%",
                "scale_y" to "${getParameter<UByte>("scaleY")}%",
                "alpha" to "${(getParameter<UByte>("alpha").toInt() / 2.55f).roundToInt()}%",
            )
            if (getParameter("loop")) {
                state.pendingAnimationIndexToStartProperties[elementIndex] = properties
                null
            } else {
                ElementLine("foreground$elementIndex", *properties.toTypedArray())
            }
        }
        "setForegroundAnimationEnd" -> {
            val elementIndex = getParameter<UByte>("index").toInt() + 1
            val foregroundElementName = "foreground$elementIndex"
            val endProperties = listOf(
                "anchor_x" to "50%",
                "position_x" to "${getParameter<Short>("centerX")}px",
                "position_y" to "${getParameter<Short>("top")}px",
                "pivot_y" to "0",
                "scale_x" to "${getParameter<UByte>("scaleX")}%",
                "scale_y" to "${getParameter<UByte>("scaleY")}%",
                "alpha" to "${(getParameter<UByte>("alpha").toInt() / 2.55f).roundToInt()}%",
            )
            val duration = getParameter<UShort>("duration")
            val startProperties =
                state.pendingAnimationIndexToStartProperties.remove(elementIndex)
            if (startProperties != null) {
                lateinit var propertyName: String
                lateinit var startValue: String
                lateinit var endValue: String
                for ((startProperty, endProperty) in startProperties.zip(endProperties)) {
                    check(startProperty.first == endProperty.first)
                    if (startProperty.second != endProperty.second) {
                        propertyName = startProperty.first
                        startValue = startProperty.second
                        endValue = endProperty.second
                        break
                    }
                }
                listOf(
                    // An infinite animation finishes at its end value in Flowers.
                    ElementLine(foregroundElementName, *endProperties.toTypedArray()),
                    CommandLine("snap", foregroundElementName),
                    ElementLine(
                        "animation$elementIndex",
                        "$foregroundElementName.$propertyName",
                        "duration" to "${duration}ms",
                        "iteration_count" to "Infinity",
                        "value_1" to startValue,
                        "value_2" to endValue,
                    ),
                )
            } else {
                listOf(
                    CommandLine("snap", foregroundElementName),
                    ElementLine(
                        foregroundElementName,
                        *(
                            endProperties + listOf(
                                "transition_duration" to "${duration}ms",
                                "transition_easing" to "linear",
                            )
                            ).toTypedArray()
                    ),
                )
            }
        }
        "playAllForegroundAnimations" -> {
            state.pendingClearMessage = false
            state.pendingClearMessageSkipName = false
            // Followed by wait
            clearMessageIfPendingLines
        }
        "stopForegroundAnimation" -> {
            val elementIndex = getParameter<UByte>("index").toInt() + 1
            val animationElementName = "animation$elementIndex"
            val foregroundElementName = "foreground$elementIndex"
            listOf(
                ElementLine(animationElementName, "none"),
                CommandLine("snap", foregroundElementName, animationElementName),
            )
        }
        "loadForegroundForAnimation" -> {
            val foregroundElementIndex = getParameter<UByte>("index").toInt() + 1
            state.foregroundForAnimationIndices += foregroundElementIndex
            val foregroundElementName = "foreground$foregroundElementIndex"
            ElementLine(foregroundElementName, value = getParameter("fileName"))
        }
        "playVideo" -> {
            val video = when (val index = getParameter<UByte>("index")) {
                0.UB -> "op"
                1.UB -> "gf"
                else -> throw IllegalArgumentException("Unknown video $index")
            }
            state.pendingClearMessage = false
            state.pendingClearMessageSkipName = false
            clearMessageIfPendingLines + listOf(
                ElementLine("video", video),
                CommandLine("wait", "video"),
                CommandLine("snap", "video"),
                ElementLine("video", "none"),
            )
        }
        "playCredits" -> CommentLine(toString())
        "setAvatar" -> ElementLine("avatar", value = getParameter("fileName"))
        "setWindowStyle" -> {
            val windowStyle = getParameter<UByte>("style")
            state.windowStyle = WindowStyle.entries[windowStyle.toInt()]
            null
        }
        "setChapter" -> CommentLine(toString())
        "decreaseMusicVolume", "increaseMusicVolume" -> {
            val volume = "${getParameter<UByte>("volume")}%"
            val duration = getParameter<UShort>("duration").toInt().let { if (it > 1) it else 0 }
            ElementLine("music", "volume" to volume, "transition_duration" to "${duration}ms")
        }
        "decreaseAllSoundEffectsVolume", "increaseAllSoundEffectsVolume" -> {
            val volume = "${getParameter<UByte>("volume")}%"
            val duration = getParameter<UShort>("duration").toInt().let { if (it > 1) it else 0 }
            ElementLine("sound*", "volume" to volume, "transition_duration" to "${duration}ms")
        }
        "playForegroundAnimations" -> {
            state.pendingClearMessage = false
            state.pendingClearMessageSkipName = false
            // Followed by wait
            clearMessageIfPendingLines
        }
        "stopForegroundAnimations" -> CommandLine("snap", "foreground*")
        else -> CommentLine(" FIXME: $this")
    }
    return when (lineOrLines) {
        null -> emptyList()
        is Line -> listOf(lineOrLines)
        is List<*> -> @Suppress("UNCHECKED_CAST") (lineOrLines as List<Line>)
        else -> error(lineOrLines)
    }
}

private val FURIGANA_REGEX = Regex("<([^<>]+)<([^<>]+)>")
private val FURIGANA_REPLACEMENT: (MatchResult) -> CharSequence = {
    "<ruby>${it.groupValues[1]}<rt>${it.groupValues[2].replace("_", "").trim()}</rt></ruby>"
}
private val IDEOGRAPHIC_SPACE_REGEX = Regex("(?<=\\p{IsHan}|[<>“”])　+(?=\\p{IsHan}|[<>“”])")
private val NEWLINE_REGEX =
    Regex("(?U)(?<=(?<![　＊])\\w([）”」》]|</ruby>)?)\n(?=(?![　＊])([（“「《]|<ruby>)?\\w(?!\\)))")
private val TRAILING_REGEX = Regex("[　\n]+$")

@Suppress("UNCHECKED_CAST")
private fun <T> Instruction.getParameter(name: String): T {
    val parameter = parameters[name] as T
    return when (name) {
        // Drop file name extension so that we can use a better file format when applicable. And
        // normalize to lower case as well.
        "fileName" -> (parameter as String).substringBeforeLast('.').lowercase() as T
        "text" ->
            (parameter as String)
                .replace('＄', '\n')
                .replace('･', ' ')
                .replace(FURIGANA_REGEX, FURIGANA_REPLACEMENT)
                .replace(IDEOGRAPHIC_SPACE_REGEX, "")
                .replace(NEWLINE_REGEX, "")
                .replace(TRAILING_REGEX, "")
                as T
        else -> parameter
    }
}

val WINDOWS_31J = Charset.forName("windows-31j")
val GBK = Charset.forName("GBK")

//val charset = WINDOWS_31J
val charset = GBK

val arg0File = File(args[0])
val arg0IsDirectory = arg0File.isDirectory
val inputFiles = if (arg0IsDirectory) {
    arg0File.listFiles { it -> it.extension == "s" }!!.toList().sorted()
} else {
    listOf(arg0File)
}
val arg1File = File(args[1])
if (arg0IsDirectory && !arg1File.exists()) {
    arg1File.mkdirs()
}
val outputFiles = if (arg0IsDirectory) {
    inputFiles.map { arg1File.resolve("${it.nameWithoutExtension}.vnm") }
} else {
    listOf(arg1File)
}
for ((inputFile, outputFile) in inputFiles.zip(outputFiles)) {
    println("$inputFile -> $outputFile")
    val instructions = mutableListOf<Instruction>()
    var currentOffset = 0.U
    val offsetToIndex = mutableMapOf<UInt, Int>()
    DataInputStream(inputFile.inputStream().buffered()).use { inputStream ->
        while (true) {
            val instruction = parseInstruction(inputStream, charset) ?: break
            offsetToIndex[currentOffset] = instructions.size
            instructions += instruction
            currentOffset += instruction.bytes.size.toUInt()
        }
    }
    val jumpTargets = instructions.flatMap { it.parameters.values.filterIsInstance<JumpTarget>() }
    val indexToJumpTarget = jumpTargets.associateBy { offsetToIndex[it.offset] }
    val state = VnmarkConversionState()
    val outputText = buildString {
        appendLine(
            """
                vnmark: 1.0.0
                blank_line:
                  - ': wait name, text*'
                  - ': snap name, text*'
                  - ': pause'
                  - ': snap voice'
            """.trimIndent())
        appendLine()
        instructions.forEachIndexed { index, instruction ->
            val jumpTarget = indexToJumpTarget[index]
            if (jumpTarget != null) {
                appendLine(CommandLine("label", jumpTarget.toString()).toString())
            }
            try {
                instruction.toVnMarkLines(state).forEach { appendLine(it.toString()) }
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "Error at instruction #${index + 1} \"$instruction\"",
                    t
                )
            }
        }
    }
    outputFile.writeText(outputText)
}
