package com.nova.callassist.assistant

import java.util.Locale

/**
 * Natural language voice command parser for Nova Assistant.
 * Fully deterministic, offline, and instant; requires no external LLMs.
 */
class CommandParser {

    sealed class Command {
        data class Call(val targetName: String, val rawText: String) : Command()
        object Confirm : Command()
        object Cancel : Command()
        object Repeat : Command()
        data class SelectOption(val index: Int) : Command()
        data class Unknown(val rawText: String) : Command()
    }

    companion object {
        private val PUNCTUATION_REGEX = Regex("[.,!?;:\"]")
        private val MULTI_SPACE_REGEX = Regex("\\s+")

        // Regex patterns for call extraction
        private val CALL_COMMAND_REGEX = Regex(
            "^(?:please\\s+)?(?:make\\s+a\\s+(?:phone\\s+)?call\\s+to|place\\s+a\\s+call\\s+to|give\\s+a\\s+call\\s+to|call\\s+up|call\\s+to|call|dial|phone)\\s+(?:my\\s+(?:friend|colleague|brother|sister|dad|mom|father|mother)\\s+)?(.+)$",
            RegexOption.IGNORE_CASE
        )

        private val CONFIRM_WORDS = setOf(
            "yes", "yep", "yeah", "sure", "confirm", "call", "call now",
            "go ahead", "please call", "okay", "ok", "do it", "correct", "proceed"
        )

        private val CANCEL_WORDS = setOf(
            "cancel", "stop", "stop listening", "no", "dont call", "don't call",
            "abort", "never mind", "nevermind", "quit", "exit", "close", "back"
        )

        private val REPEAT_WORDS = setOf(
            "repeat", "repeat the name", "say again", "who", "who is it", "pardon", "repeat that"
        )

        private val NUMBER_WORDS = mapOf(
            "one" to 1,
            "first" to 1,
            "1" to 1,
            "1st" to 1,
            "two" to 2,
            "second" to 2,
            "2" to 2,
            "2nd" to 2,
            "three" to 3,
            "third" to 3,
            "3" to 3,
            "3rd" to 3,
            "four" to 4,
            "fourth" to 4,
            "4" to 4,
            "4th" to 4,
            "five" to 5,
            "fifth" to 5,
            "5" to 5,
            "5th" to 5
        )
    }

    /**
     * Parses spoken text into a structured command.
     */
    fun parse(rawText: String): Command {
        val clean = rawText.lowercase(Locale.getDefault())
            .replace(PUNCTUATION_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

        if (clean.isBlank()) {
            return Command.Unknown(rawText)
        }

        // Check for cancel / stop first
        if (clean in CANCEL_WORDS || clean.startsWith("stop ") || clean == "stop") {
            return Command.Cancel
        }

        // Check for confirm words
        if (clean in CONFIRM_WORDS) {
            return Command.Confirm
        }

        // Check for repeat requests
        if (clean in REPEAT_WORDS) {
            return Command.Repeat
        }

        // Check for option selection (e.g., "option 1", "number two", "second one", "first")
        val optionIndex = parseOptionSelection(clean)
        if (optionIndex != null) {
            return Command.SelectOption(optionIndex)
        }

        // Check for Call command
        val match = CALL_COMMAND_REGEX.find(clean)
        if (match != null) {
            val target = match.groupValues[1].trim()
            if (target.isNotBlank()) {
                return Command.Call(targetName = target, rawText = rawText)
            }
        }

        // Fallback: If user speaks just a name after wake-word prompt (e.g. prompt was "Who would you like to call?"),
        // handle direct contact name if not a known control word
        if (clean.split(" ").size <= 4 && clean !in CANCEL_WORDS && clean !in CONFIRM_WORDS) {
            return Command.Call(targetName = clean, rawText = rawText)
        }

        return Command.Unknown(rawText)
    }

    private fun parseOptionSelection(text: String): Int? {
        val cleaned = text
            .removePrefix("option ")
            .removePrefix("number ")
            .removeSuffix(" one")
            .trim()

        return NUMBER_WORDS[cleaned]
    }
}
