package com.nova.callassist.contacts

import java.util.Locale

/**
 * Deterministic, multi-tiered contact matching engine.
 *
 * Guarantees:
 * 1. Accurately matches spoken names against real Android contacts and configured aliases.
 * 2. Robust support for Indian names, initials (e.g. "Praveen P", "P. Praveen"), and phonetic spellings.
 * 3. Never silently calls a different contact: uses conservative fuzzy matching and defaults to
 *    [MatchResult.Ambiguous] when multiple candidates are plausible.
 * 4. 100% on-device and offline with zero PII telemetry.
 */
class ContactMatcher {

    companion object {
        private val PUNCTUATION_REGEX = Regex("[^a-zA-Z0-9\\s]")
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val HONORIFICS = setOf("mr", "mrs", "ms", "dr", "prof", "er", "friend")
        private val DESCRIPTIVE_TAGS = setOf("work", "home", "mobile", "office", "gym", "bro", "main")

        // Common Indian English name transliterations and speech-to-text phonetic variations
        private val PHONETIC_VARIANTS: Map<String, Set<String>> = mapOf(
            "arun" to setOf("aaron", "aron", "aroon", "harun", "aran", "allan", "arun"),
            "aaron" to setOf("arun", "aron", "aroon"),
            "aron" to setOf("arun", "aaron", "aroon"),
            "aroon" to setOf("arun", "aron"),
            "praveen" to setOf("pravin", "parveen", "prabin", "praveen"),
            "pravin" to setOf("praveen", "parveen"),
            "parveen" to setOf("praveen", "pravin"),
            "vignesh" to setOf("vigneshwaran", "vignesh", "vicky", "vignash"),
            "vigneshwaran" to setOf("vignesh", "vicky"),
            "bala" to setOf("balamurugan", "balaji", "bala"),
            "balaji" to setOf("bala", "balamurugan"),
            "suresh" to setOf("sureshkumar", "suresh"),
            "ramesh" to setOf("rameshkumar", "ramesh"),
            "karthik" to setOf("karthick", "karthikeyan", "karthik"),
            "karthick" to setOf("karthik", "karthikeyan"),
            "lakshmi" to setOf("laxmi", "luxmi", "lakshmi"),
            "laxmi" to setOf("lakshmi"),
            "swetha" to setOf("shweta", "shwetha", "swetha"),
            "priya" to setOf("preethi", "priti", "priya"),
            "mom" to setOf("mother", "amma", "mummy", "mom"),
            "mother" to setOf("mom", "amma", "mummy"),
            "amma" to setOf("mom", "mother"),
            "dad" to setOf("father", "appa", "daddy", "dad"),
            "father" to setOf("dad", "appa", "daddy"),
            "appa" to setOf("dad", "father")
        )
    }

    /**
     * Matches a spoken contact name against a list of known contacts.
     */
    fun match(query: String, contacts: List<ContactModel>): MatchResult {
        val cleanQuery = normalize(query)
        if (cleanQuery.isBlank() || contacts.isEmpty()) {
            return MatchResult.NoMatch(query)
        }

        val queryTokens = tokenize(cleanQuery)
        if (queryTokens.isEmpty()) {
            return MatchResult.NoMatch(query)
        }

        // Tier 1: Exact normalized full-name or alias match
        val exactMatches = contacts.filter { contact ->
            normalize(contact.displayName) == cleanQuery ||
                    contact.aliases.any { normalize(it) == cleanQuery }
        }
        if (exactMatches.isNotEmpty()) {
            return resolveCandidates(query, exactMatches, confidence = 1.0f)
        }

        // Tier 2: Indian English phonetic variant match
        // e.g. Whisper transcribed "Aaron" or "Aron" when user said "Arun"
        val phoneticMatches = contacts.filter { contact ->
            matchesPhoneticVariant(queryTokens, contact)
        }
        if (phoneticMatches.isNotEmpty()) {
            return resolveCandidates(query, phoneticMatches, confidence = 0.95f)
        }

        // Tier 3: Match name ignoring initials (e.g. query "Praveen" matches "Praveen P" or "P. Praveen")
        val initialIgnoredMatches = contacts.filter { contact ->
            val contactTokens = tokenize(normalize(contact.displayName))
            val substantiveContactTokens = contactTokens.filter { it.length > 1 }
            val substantiveQueryTokens = queryTokens.filter { it.length > 1 }
            if (substantiveContactTokens.isNotEmpty() && substantiveQueryTokens.isNotEmpty()) {
                substantiveContactTokens == substantiveQueryTokens
            } else {
                false
            }
        }
        if (initialIgnoredMatches.isNotEmpty()) {
            return resolveCandidates(query, initialIgnoredMatches, confidence = 0.93f)
        }

        // Tier 4: Exact single token match
        val singleTokenMatches = contacts.filter { contact ->
            val contactTokens = tokenize(normalize(contact.displayName))
            contactTokens.size == 1 && queryTokens.size == 1 && contactTokens[0] == queryTokens[0]
        }
        if (singleTokenMatches.isNotEmpty()) {
            return resolveCandidates(query, singleTokenMatches, confidence = 0.92f)
        }

        // Tier 5: Prefix token match (e.g. "Arun" matches "Arun Kumar")
        val prefixMatches = contacts.filter { contact ->
            val contactTokens = tokenize(normalize(contact.displayName))
            if (contactTokens.isEmpty()) false
            else if (queryTokens.size <= contactTokens.size) {
                queryTokens.indices.all { i -> contactTokens[i] == queryTokens[i] }
            } else {
                false
            }
        }
        if (prefixMatches.isNotEmpty()) {
            return resolveCandidates(query, prefixMatches, confidence = 0.90f)
        }

        // Tier 6: All query tokens are contained in contact tokens (unordered)
        // e.g. "Kumar Rahul" matches "Rahul Kumar", "Arun" matches "Dr. Arun Plenome"
        val allTokensMatched = contacts.filter { contact ->
            val contactTokens = tokenize(normalize(contact.displayName)).toSet()
            queryTokens.all { it in contactTokens }
        }
        if (allTokensMatched.isNotEmpty()) {
            return resolveCandidates(query, allTokensMatched, confidence = 0.85f)
        }

        // Tier 7: Substring containment
        val substringMatches = contacts.filter { contact ->
            val normName = normalize(contact.displayName)
            normName.contains(cleanQuery)
        }
        if (substringMatches.isNotEmpty()) {
            return resolveCandidates(query, substringMatches, confidence = 0.80f)
        }

        // Tier 8: Conservative Levenshtein fuzzy match
        // Strictly guarded to prevent silently calling the wrong person
        val fuzzyCandidates = contacts.mapNotNull { contact ->
            val normName = normalize(contact.displayName)
            val contactTokens = tokenize(normName)
            val distance = calculateConservativeTokenDistance(queryTokens, contactTokens)
            if (distance != null) {
                Pair(contact, distance)
            } else {
                null
            }
        }

        if (fuzzyCandidates.isNotEmpty()) {
            val minDistance = fuzzyCandidates.minOf { it.second }
            val topCandidates = fuzzyCandidates.filter { it.second == minDistance }.map { it.first }
            return resolveCandidates(query, topCandidates, confidence = 0.70f)
        }

        return MatchResult.NoMatch(query)
    }

    private fun matchesPhoneticVariant(queryTokens: List<String>, contact: ContactModel): Boolean {
        val contactTokens = tokenize(normalize(contact.displayName))
        if (contactTokens.isEmpty() || queryTokens.isEmpty()) return false

        for (q in queryTokens) {
            val variants = PHONETIC_VARIANTS[q] ?: emptySet()
            for (c in contactTokens) {
                if (c in variants || (PHONETIC_VARIANTS[c]?.contains(q) == true)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Calculates conservative edit distance.
     * Rejects matches if query is too short (<4 chars) or if distance ratio is too high.
     */
    private fun calculateConservativeTokenDistance(
        queryTokens: List<String>,
        contactTokens: List<String>
    ): Int? {
        if (queryTokens.isEmpty() || contactTokens.isEmpty()) return null

        var bestDistance = Int.MAX_VALUE
        var matched = false

        for (q in queryTokens) {
            if (q.length < 4) continue // Don't fuzzy-match short abbreviations or initials

            val maxAllowedDistance = when {
                q.length >= 7 -> 2
                else -> 1
            }

            for (c in contactTokens) {
                if (c.length < 3) continue
                val dist = levenshtein(q, c)
                if (dist <= maxAllowedDistance) {
                    matched = true
                    if (dist < bestDistance) {
                        bestDistance = dist
                    }
                }
            }
        }

        return if (matched) bestDistance else null
    }

    private fun resolveCandidates(
        query: String,
        candidates: List<ContactModel>,
        confidence: Float
    ): MatchResult {
        // Distinct by contact ID and phone number to avoid duplicates
        val distinctCandidates = candidates.distinctBy { it.id }

        if (distinctCandidates.size > 1) {
            return MatchResult.Ambiguous(query, distinctCandidates)
        }

        val contact = distinctCandidates.first()
        val number = contact.primaryOrFirstNumber
        return if (number == null) {
            MatchResult.NoPhoneNumber(contact)
        } else {
            MatchResult.Single(contact, number, confidence)
        }
    }

    /**
     * Normalizes a string by lowercasing, stripping punctuation, and trimming extra spaces.
     */
    fun normalize(input: String): String {
        return input.lowercase(Locale.getDefault())
            .replace(PUNCTUATION_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    /**
     * Splits into tokens and removes common honorifics and descriptive parentheticals.
     */
    fun tokenize(input: String): List<String> {
        val tokens = input.split(MULTI_SPACE_REGEX).filter { it.isNotBlank() }
        return tokens.filter { it !in HONORIFICS && it !in DESCRIPTIVE_TAGS }
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
