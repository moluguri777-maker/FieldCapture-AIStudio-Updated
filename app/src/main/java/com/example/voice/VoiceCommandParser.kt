package com.example.voice

import com.example.domain.model.CaptureCommandType

sealed interface ParsedVoiceCommand {
    val rawTranscript: String
    val commandType: CaptureCommandType

    data class CaptureNameplate(
        override val rawTranscript: String
    ) : ParsedVoiceCommand {
        override val commandType: CaptureCommandType = CaptureCommandType.NAMEPLATE
    }

    data class CaptureDamage(
        val room: String,
        val surface: String,
        override val rawTranscript: String
    ) : ParsedVoiceCommand {
        override val commandType: CaptureCommandType = CaptureCommandType.DAMAGE
    }

    data class TechnicianNote(
        val noteText: String,
        override val rawTranscript: String
    ) : ParsedVoiceCommand {
        override val commandType: CaptureCommandType = CaptureCommandType.NOTE
    }
}

sealed interface VoiceParseResult {
    data class Matched(val command: ParsedVoiceCommand) : VoiceParseResult
    data class MissingArguments(
        val commandName: String,
        val rawTranscript: String,
        val hint: String
    ) : VoiceParseResult

    data class Unrecognized(val rawTranscript: String, val hint: String) : VoiceParseResult
}

/**
 * Extensible command-handler contract so adding a 4th hands-free command requires registering
 * a new VoiceCommandSpec implementation in [VoiceCommandParser.defaultSpecs].
 */
interface VoiceCommandSpec {
    val commandName: String
    val usageHint: String
    fun tryParse(normalizedTranscript: String, originalTranscript: String): VoiceParseResult?
}

object VoiceCommandParser {

    private val nameplateSpec = object : VoiceCommandSpec {
        override val commandName: String = "capture nameplate"
        override val usageHint: String = "capture nameplate"
        private val pattern = Regex(
            """^(?:capture|take|scan|snap)?\s*(?:nameplate|name\s*plate|data\s*plate|serial\s*plate|rating\s*plate)\b.*$""",
            RegexOption.IGNORE_CASE
        )

        override fun tryParse(normalizedTranscript: String, originalTranscript: String): VoiceParseResult? {
            if (!pattern.matches(normalizedTranscript)) return null
            return VoiceParseResult.Matched(
                ParsedVoiceCommand.CaptureNameplate(originalTranscript)
            )
        }
    }

    private val noteSpec = object : VoiceCommandSpec {
        override val commandName: String = "note"
        override val usageHint: String = "note: [free text]"
        private val headerOnlyPattern = Regex("""^note\s*[:\-]?$""", RegexOption.IGNORE_CASE)
        private val notePattern = Regex("""^note\s*[:\-]?\s+(.+)$""", RegexOption.IGNORE_CASE)
        private val colonNotePattern = Regex("""^note\s*[:\-]\s*(.+)$""", RegexOption.IGNORE_CASE)

        override fun tryParse(normalizedTranscript: String, originalTranscript: String): VoiceParseResult? {
            if (headerOnlyPattern.matches(normalizedTranscript)) {
                return VoiceParseResult.MissingArguments(
                    commandName = commandName,
                    rawTranscript = originalTranscript,
                    hint = "Missing note text. Say or type 'note: [observation]'."
                )
            }
            val match = colonNotePattern.find(normalizedTranscript) ?: notePattern.find(normalizedTranscript)
                ?: return null
            val body = match.groupValues[1].trim()
            if (body.isEmpty()) {
                return VoiceParseResult.MissingArguments(
                    commandName = commandName,
                    rawTranscript = originalTranscript,
                    hint = "Missing note text. Say or type 'note: [observation]'."
                )
            }
            return VoiceParseResult.Matched(
                ParsedVoiceCommand.TechnicianNote(
                    noteText = body,
                    rawTranscript = originalTranscript
                )
            )
        }
    }

    private val damageSpec = object : VoiceCommandSpec {
        override val commandName: String = "capture damage"
        override val usageHint: String = "capture damage [room] [surface]"
        private val prefixPattern = Regex(
            """^(?:capture|take|log)?\s*damage\b(.*)$""",
            RegexOption.IGNORE_CASE
        )

        override fun tryParse(normalizedTranscript: String, originalTranscript: String): VoiceParseResult? {
            val match = prefixPattern.find(normalizedTranscript) ?: return null
            var remainder = match.groupValues[1].trim()
            remainder = remainder.replace(Regex("""^(?:in|on|at)\s+""", RegexOption.IGNORE_CASE), "").trim()

            if (remainder.isEmpty()) {
                return VoiceParseResult.MissingArguments(
                    commandName = commandName,
                    rawTranscript = originalTranscript,
                    hint = "Missing room and surface. Say 'capture damage [room] [surface]', e.g. 'capture damage basement drywall'."
                )
            }

            val tokens = remainder.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (tokens.size < 2) {
                val roomCandidate = normalizeLabel(tokens[0])
                return VoiceParseResult.MissingArguments(
                    commandName = commandName,
                    rawTranscript = originalTranscript,
                    hint = "Missing surface for room '$roomCandidate'. Say 'capture damage $roomCandidate [surface]'."
                )
            }

            val room = normalizeLabel(tokens[0])
            val surface = tokens.drop(1).joinToString(" ") { normalizeLabel(it) }
            return VoiceParseResult.Matched(
                ParsedVoiceCommand.CaptureDamage(
                    room = room,
                    surface = surface,
                    rawTranscript = originalTranscript
                )
            )
        }
    }

    val defaultSpecs: List<VoiceCommandSpec> = listOf(
        nameplateSpec,
        noteSpec,
        damageSpec
    )

    fun parse(
        transcript: String,
        specs: List<VoiceCommandSpec> = defaultSpecs
    ): VoiceParseResult {
        val trimmed = transcript.trim()
        // Strip trailing ASR dictation punctuation (. ! ?) while keeping internal text intact.
        val normalized = trimmed.trimEnd { it == '.' || it == '!' || it == '?' }.trim()
        if (normalized.isEmpty()) {
            return VoiceParseResult.Unrecognized(
                rawTranscript = transcript,
                hint = "Say: ${specs.joinToString(", ") { "'${it.usageHint}'" }}"
            )
        }

        for (spec in specs) {
            val result = spec.tryParse(normalized, trimmed)
            if (result != null) return result
        }

        return VoiceParseResult.Unrecognized(
            rawTranscript = trimmed,
            hint = "Unrecognized command '$trimmed'. Expected ${specs.joinToString(", ") { "'${it.usageHint}'" }}."
        )
    }

    private fun normalizeLabel(token: String): String =
        token.trim().lowercase().replaceFirstChar { it.uppercase() }
}
