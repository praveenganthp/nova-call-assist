package com.nova.callassist.assistant

import java.util.Locale

/**
 * Natural language voice command parser for Nova Assistant.
 * Fully deterministic, offline, and instant; handles speech-to-text variations,
 * conversational prefixes ("Can you call...", "Please call..."), trailing fillers,
 * and direct contact names.
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
        private val PUNCTUATION_REGEX = Regex("[.,!?;:\"'\\-_/\\\\]")
        private val MULTI_SPACE_REGEX = Regex("\\s+")

        // Ordered from longest to shortest to ensure greedy prefix matching
        private val CALL_PREFIXES = listOf(
            "can you please make a call to",
            "could you please make a call to",
            "can you please call",
            "could you please call",
            "would you please call",
            "can you make a call to",
            "could you make a call to",
            "can you call",
            "could you call",
            "would you call",
            "will you call",
            "i would like to call",
            "i want to call",
            "i need to call",
            "make a phone call to",
            "place a phone call to",
            "make a call to",
            "place a call to",
            "give a call to",
            "connect me to",
            "please call",
            "please dial",
            "please phone",
            "ring up",
            "call up",
            "call to",
            "dial to",
            "call",
            "dial",
            "phone",
            "ring"
        )

        private val LEADING_WAKE_WORDS = listOf(
            "hey nova",
            "hi nova",
            "hello nova",
            "ok nova",
            "okay nova",
            "hey noah",
            "nova"
        )

        private val RELATION_PREFIXES = listOf(
            "my friend",
            "my colleague",
            "my brother",
            "my sister",
            "my dad",
            "my mom",
            "my father",
            "my mother",
            "my wife",
            "my husband"
        )

        private val TRAILING_FILLERS = listOf(
            "please",
            "for me",
            "right now",
            "now",
            "on mobile",
            "on phone",
            "on cellular",
            "urgently",
            "asap",
            "quickly"
        )

        private val CONFIRM_WORDS = setOf(
            "yes", "yep", "yeah", "sure", "confirm", "call now",
            "go ahead", "okay", "ok", "do it", "correct", "proceed"
        )

        private val CANCEL_WORDS = setOf(
            "cancel", "stop", "stop listening", "no", "dont call", "don't call", "don t call",
            "abort", "never mind", "nevermind", "quit", "exit", "close", "back", "dismiss",
            "dont", "don't", "no call"
        )

        private val NON_CALL_STARTS = listOf(
            "what ", "how ", "why ", "when ", "where ", "open ", "play ",
            "search ", "show ", "tell ", "turn ", "set ", "launch "
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
        var clean = rawText.lowercase(Locale.getDefault())
            .replace("'", "")
            .replace(PUNCTUATION_REGEX, " ")
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

        // Check for incomplete commands, bare wake-words, or non-calling conversational queries
        if (clean in CALL_PREFIXES || clean in LEADING_WAKE_WORDS || NON_CALL_STARTS.any { clean.startsWith(it) }) {
            return Command.Unknown(rawText)
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

        // Strip leading wake-phrase if recognized as part of user utterance (e.g. "Hey Nova, call Arun")
        for (wakeWord in LEADING_WAKE_WORDS) {
            if (clean.startsWith("$wakeWord ")) {
                clean = clean.removePrefix("$wakeWord ").trim()
                break
            }
        }

        // Strip call action prefix
        var targetCandidate: String? = null
        for (prefix in CALL_PREFIXES) {
            if (clean.startsWith("$prefix ")) {
                targetCandidate = clean.removePrefix("$prefix ").trim()
                break
            }
        }

        if (targetCandidate != null) {
            val target = cleanTargetName(targetCandidate)
            if (target.isNotBlank()) {
                return Command.Call(targetName = target, rawText = rawText)
            }
        }

        // Fallback: If user speaks just a name (or "Praveen please", "Arun") after wake prompt
        val directTarget = cleanTargetName(clean)
        val tokenCount = directTarget.split(MULTI_SPACE_REGEX).filter { it.isNotBlank() }.size
        if (tokenCount in 1..4 &&
            directTarget !in CANCEL_WORDS &&
            directTarget !in CONFIRM_WORDS &&
            directTarget !in CALL_PREFIXES &&
            directTarget !in LEADING_WAKE_WORDS &&
            !NON_CALL_STARTS.any { directTarget.startsWith(it) }
        ) {
            return Command.Call(targetName = directTarget, rawText = rawText)
        }

        return Command.Unknown(rawText)
    }

    /**
     * Strips relationship qualifiers ("my friend") and trailing fillers ("please", "now").
     */
    private fun cleanTargetName(targetRaw: String): String {
        var result = targetRaw.trim()

        // Strip relation prefix
        for (rel in RELATION_PREFIXES) {
            if (result.startsWith("$rel ")) {
                result = result.removePrefix("$rel ").trim()
                break
            }
        }

        // Strip trailing conversational fillers
        var changed = true
        while (changed) {
            changed = false
            for (filler in TRAILING_FILLERS) {
                if (result.endsWith(" $filler")) {
                    result = result.removeSuffix(" $filler").trim()
                    changed = true
                    break
                }
            }
        }

        return result
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
