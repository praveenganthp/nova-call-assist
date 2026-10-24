package com.nova.callassist.contacts

import java.util.Locale

/**
 * Deterministic, multi-tiered contact matching engine.
 * Never uploads contacts and runs entirely locally on-device.
 */
class ContactMatcher {

    companion object {
        private val PUNCTUATION_REGEX = Regex("[^a-zA-Z0-9\\s]")
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val COMMON_PREFIXES = setOf("mr", "mrs", "ms", "dr", "prof", "friend")
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

        // Tier 1: Exact normalized full-name match
        val exactMatches = contacts.filter { normalize(it.displayName) == cleanQuery }
        if (exactMatches.isNotEmpty()) {
            return resolveCandidates(query, exactMatches, confidence = 1.0f)
        }

        // Tier 2: Exact first-name or single token matches
        // e.g. Contact "Arun" when user asks for "Arun"
        val exactTokenMatches = contacts.filter { contact ->
            val contactTokens = tokenize(normalize(contact.displayName))
            contactTokens.size == 1 && queryTokens.size == 1 && contactTokens[0] == queryTokens[0]
        }
        if (exactTokenMatches.isNotEmpty()) {
            return resolveCandidates(query, exactTokenMatches, confidence = 0.98f)
        }

        // Tier 3: Query tokens match the start of contact tokens (e.g. "Arun" -> "Arun Kumar", "Arun Prakash")
        val prefixMatches = contacts.filter { contact ->
            val contactTokens = tokenize(normalize(contact.displayName))
            if (contactTokens.isEmpty()) false
            else if (queryTokens.size <= contactTokens.size) {
                queryTokens.indices.all { i -> contactTokens[i] == queryTokens[i] }
            } else {
                false
            }
        }
        if (prefixMatches.size == 1) {
            return resolveCandidates(query, prefixMatches, confidence = 0.90f)
        } else if (prefixMatches.size > 1) {
            return MatchResult.Ambiguous(query, prefixMatches)
        }

        // Tier 4: All query tokens are contained in contact tokens (unordered)
        // e.g. "Kumar Rahul" matches "Rahul Kumar"
        val allTokensMatched = contacts.filter { contact ->
            val contactTokens = tokenize(normalize(contact.displayName)).toSet()
            queryTokens.all { it in contactTokens }
        }
        if (allTokensMatched.size == 1) {
            return resolveCandidates(query, allTokensMatched, confidence = 0.85f)
        } else if (allTokensMatched.size > 1) {
            return MatchResult.Ambiguous(query, allTokensMatched)
        }

        // Tier 5: Substring containment (e.g. "Arun" inside "Dr. Arun")
        val substringMatches = contacts.filter { contact ->
            val normName = normalize(contact.displayName)
            normName.contains(cleanQuery)
        }
        if (substringMatches.size == 1) {
            return resolveCandidates(query, substringMatches, confidence = 0.80f)
        } else if (substringMatches.size > 1) {
            return MatchResult.Ambiguous(query, substringMatches)
        }

        // Tier 6: Levenshtein distance for speech recognition variances (e.g. "Aron" -> "Arun")
        val fuzzyMatches = contacts.mapNotNull { contact ->
            val normName = normalize(contact.displayName)
            val minDistance = calculateMinTokenDistance(queryTokens, tokenize(normName))
            val threshold = if (cleanQuery.length >= 6) 2 else 1
            if (minDistance <= threshold) {
                Pair(contact, minDistance)
            } else {
                null
            }
        }

        if (fuzzyMatches.isNotEmpty()) {
            val bestDistance = fuzzyMatches.minOf { it.second }
            val topCandidates = fuzzyMatches.filter { it.second == bestDistance }.map { it.first }
            if (topCandidates.size == 1) {
                return resolveCandidates(query, topCandidates, confidence = 0.70f)
            } else {
                return MatchResult.Ambiguous(query, topCandidates)
            }
        }

        return MatchResult.NoMatch(query)
    }

    private fun resolveCandidates(
        query: String,
        candidates: List<ContactModel>,
        confidence: Float
    ): MatchResult {
        if (candidates.size > 1) {
            return MatchResult.Ambiguous(query, candidates)
        }

        val contact = candidates.first()
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
     * Splits into tokens and removes common conversational prefixes.
     */
    fun tokenize(input: String): List<String> {
        val tokens = input.split(MULTI_SPACE_REGEX).filter { it.isNotBlank() }
        return tokens.filter { it !in COMMON_PREFIXES }
    }

    /**
     * Finds the minimum Levenshtein edit distance between token sets.
     */
    private fun calculateMinTokenDistance(queryTokens: List<String>, contactTokens: List<String>): Int {
        if (queryTokens.isEmpty() || contactTokens.isEmpty()) return Int.MAX_VALUE
        var minDistance = Int.MAX_VALUE
        for (q in queryTokens) {
            for (c in contactTokens) {
                val dist = levenshtein(q, c)
                if (dist < minDistance) {
                    minDistance = dist
                }
            }
        }
        return minDistance
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
