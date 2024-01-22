#!/usr/bin/env kotlin

import java.io.File

// https://datatracker.ietf.org/doc/html/rfc4180
fun String.escapeForCsv(): String {
    if (all { it !in "\r\n\"," }) {
        return this
    }
    return buildString {
        append('"')
        for (c in this@escapeForCsv) {
            if (c == '"') {
                append('"')
            }
            append(c)
        }
        append('"')
    }
}

class ScriptState(
    val characterIndexToName: MutableMap<String, String> = mutableMapOf(),
    val characterNameToIndex: MutableMap<String, String> = mutableMapOf(),
    val characterIndexToPath: MutableMap<String, MutableList<String>> = mutableMapOf(),
    var showFace: Boolean = true,
    var face: String? = null,
    var voice: String? = null,
    var names: List<String>? = null,
    var messages: List<String>? = null
)

val arg0File = File(args[0])
val arg0IsDirectory = arg0File.isDirectory
val inputFiles = if (arg0IsDirectory) {
    arg0File.listFiles()!!.toList().sorted()
} else {
    listOf(arg0File)
}
val arg1File = File(args[1])
if (arg0IsDirectory && !arg1File.exists()) {
    arg1File.mkdirs()
}
val outputFiles = if (arg0IsDirectory) {
    inputFiles.map { arg1File.resolve("${it.nameWithoutExtension}.csv") }
} else {
    listOf(arg1File)
}
for ((inputFile, outputFile) in inputFiles.zip(outputFiles)) {
    outputFile.bufferedWriter().use { writer ->
        val scriptState = ScriptState()
        fun writeLineIfNeeded() {
            val messages = scriptState.messages ?: return
            writer.apply {
                val face =
                    if (scriptState.showFace) {
                        scriptState.face ?: scriptState.names?.get(0)
                            ?.let { scriptState.characterNameToIndex[it] }
                            ?.let { scriptState.characterIndexToPath[it] }
                                ?.joinToString(separator = "")
                    } else {
                        null
                    }
                write(face.orEmpty().escapeForCsv())
                write(','.toInt())
                write(scriptState.voice.orEmpty().escapeForCsv())
                val names =
                    scriptState.names?.also {
                        require(it.size == messages.size) { "Inconsistent number of translations" }
                    } ?: MutableList(scriptState.messages!!.size) { "" }
                names.zip(messages).forEach { (name, message) ->
                    write(','.toInt())
                    write(name.escapeForCsv())
                    write(','.toInt())
                    write(message.escapeForCsv())
                }
                write("\r\n")
            }
            scriptState.apply {
                face = null
                voice = null
                names = null
                this.messages = null
            }
        }
        inputFile.forEachLine { line ->
            val line = line.trim { it == ' ' || it == '\t' }
            if (line.startsWith('％')) {
                writeLineIfNeeded()
                scriptState.voice = line.substring(1)
            } else if (line.startsWith('【')) {
                writeLineIfNeeded()
                scriptState.names = line.split('※').map { it.substring(1, it.length - 1) }
            } else if (line.startsWith("^chara")) {
                val arguments = line.split(',')
                val index = arguments[0].removePrefix("^chara")
                val pathSegments =
                    scriptState.characterIndexToPath.getOrPut(index) { mutableListOf() }
                for (argument in arguments) {
                    if (argument.startsWith("file")) {
                        val (pathSegmentIndexString, pathSegment) =
                            argument.removePrefix("file").split(':', limit = 2)
                        val pathSegmentIndex = pathSegmentIndexString.toInt()
                        require(pathSegmentIndex <= pathSegments.size) {
                            "Invalid character file index"
                        }
                        if (pathSegment == "none") {
                            pathSegments.subList(pathSegmentIndex, pathSegments.size).clear()
                            break
                        }
                        if (pathSegmentIndex < pathSegments.size) {
                            pathSegments[pathSegmentIndex] = pathSegment
                        } else {
                            pathSegments += pathSegment
                        }
                    }
                }
                if (pathSegments.isEmpty()) {
                    scriptState.apply {
                        characterIndexToName.remove(index)?.let { characterNameToIndex -= it }
                        characterIndexToPath -= index
                    }
                } else {
                    scriptState.names?.get(0)?.let { name ->
                        if (name !in scriptState.characterNameToIndex) {
                            scriptState.apply {
                                characterIndexToName[index]?.let { characterNameToIndex -= it }
                                characterIndexToName[index] = name
                                characterNameToIndex[name] = index
                            }
                        }
                    }
                }
            } else if (line.startsWith("^face")) {
                val arguments = line.split(',')
                for (argument in arguments) {
                    if (argument.startsWith("file:")) {
                        scriptState.face = argument.removePrefix("file:").takeIf { it != "none" }
                    } else if (argument.startsWith("show:")) {
                        scriptState.showFace = argument.removePrefix("show:").toBooleanStrict()
                    }
                }
            } else if (line.isEmpty() || line[0] in "/@^\\") {
                // Skip.
            } else {
                writeLineIfNeeded()
                scriptState.messages = line.split('※')
            }
        }
        writeLineIfNeeded()
    }
}
