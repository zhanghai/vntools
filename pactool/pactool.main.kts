#!/usr/bin/env kotlin

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

fun DataInputStream.readLittleEndianLong(): Long = java.lang.Long.reverseBytes(readLong())

fun InputStream.copyTo(out: OutputStream, size: Long, bufferSize: Int = DEFAULT_BUFFER_SIZE) {
    var bytesCopied = 0L
    val buffer = ByteArray(bufferSize)
    while (bytesCopied < size) {
        var bytesRead = read(buffer, 0, bufferSize.coerceAtMost(size - bytesCopied))
        if (bytesRead < 0) {
            break
        }
        out.write(buffer, 0, bytesRead)
        bytesCopied += bytesRead
    }
    if (bytesCopied < size) {
        throw EOFException()
    }
}

fun InputStream.isEof(): Boolean {
    mark(1)
    try {
        return read() == -1
    } finally {
        reset()
    }
}

fun Int.coerceAtMost(maximumValue: Long): Int =
    coerceAtMost(maximumValue.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

val SIGNATURE = "PAC VER-1.00\u0000\u0000\u0000\u0000".toByteArray()

val inputFile = File(args[0])
val outputDirectory = File(args[1])
DataInputStream(inputFile.inputStream().buffered()).use { inputStream ->
    var offset = 0L
    val signature = ByteArray(16).apply { inputStream.readFully(this) }.also { offset += it.size }
    require(signature contentEquals SIGNATURE) { "Signature mismatch" }
    val headerEntryOffsets = mutableListOf<Long>()
    val firstEntryOffset = inputStream.readLittleEndianLong().also { offset += Long.SIZE_BYTES }
    headerEntryOffsets += firstEntryOffset
    while (offset < firstEntryOffset) {
        val entryOffset = inputStream.readLittleEndianLong().also { offset += Long.SIZE_BYTES }
        if (entryOffset == 0L) {
            break
        }
        headerEntryOffsets += entryOffset
    }
    inputStream.skipNBytes(firstEntryOffset - offset).also { offset = firstEntryOffset }
    if (!outputDirectory.exists()) {
        outputDirectory.mkdirs()
    }
    val entryOffsets = mutableSetOf<Long>()
    while (!inputStream.isEof()) {
        entryOffsets += offset
        val fileNameBytes = ByteArray(20).apply { inputStream.readFully(this) }.also { offset += it.size }
        val fileName =
            fileNameBytes.decodeToString(
                endIndex = fileNameBytes.indexOf(0).takeIf { it != -1 } ?: fileNameBytes.size
            )
        val entrySize = inputStream.readLittleEndianLong().also { offset += Long.SIZE_BYTES }
        val fileSize = entrySize - fileNameBytes.size - Long.SIZE_BYTES
        System.err.println("Extracting $fileName ($fileSize bytes)...")
        val outputFile = outputDirectory.resolve(fileName)
        outputFile.outputStream().buffered().use { outputStream ->
            inputStream.copyTo(outputStream, size = fileSize).also { offset += fileSize }
        }
    }
    if (!entryOffsets.containsAll(headerEntryOffsets)) {
        System.err.println("Warning: Entry offsets mismatch with file header")
    }
}
