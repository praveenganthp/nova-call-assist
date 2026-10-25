package com.nova.callassist.contacts

/**
 * Represents a single phone number associated with a contact.
 */
data class PhoneNumber(
    val number: String,
    val type: String,
    val isPrimary: Boolean = false
) {
    /**
     * Sanitized digits-and-plus representation suitable for dialing.
     */
    val dialableNumber: String
        get() = number.replace(Regex("[^0-9+]"), "")

    /**
     * Masked display format (e.g. "+91 •••• •• 4821") to protect privacy in UI.
     */
    val maskedNumber: String
        get() {
            val clean = dialableNumber
            if (clean.length <= 4) return clean
            val visibleDigits = 4
            val maskedPart = "•".repeat((clean.length - visibleDigits).coerceAtLeast(4))
            val tail = clean.takeLast(visibleDigits)
            return "$maskedPart $tail"
        }
}

/**
 * Represents a contact retrieved from the Android Contacts Provider.
 */
data class ContactModel(
    val id: String,
    val lookupKey: String,
    val displayName: String,
    val phoneNumbers: List<PhoneNumber>,
    val photoUri: String? = null,
    val aliases: List<String> = emptyList()
) {
    val primaryOrFirstNumber: PhoneNumber?
        get() = phoneNumbers.firstOrNull { it.isPrimary } ?: phoneNumbers.firstOrNull()

    val hasPhoneNumber: Boolean
        get() = phoneNumbers.isNotEmpty()
}

/**
 * Result of matching a spoken query against local contacts.
 */
sealed class MatchResult {
    /**
     * Exactly one contact match with high confidence.
     */
    data class Single(
        val contact: ContactModel,
        val selectedNumber: PhoneNumber,
        val confidence: Float
    ) : MatchResult()

    /**
     * Multiple plausible matches found (ambiguity). User must choose.
     */
    data class Ambiguous(
        val query: String,
        val candidates: List<ContactModel>
    ) : MatchResult()

    /**
     * Contact was identified, but has no associated phone number.
     */
    data class NoPhoneNumber(
        val contact: ContactModel
    ) : MatchResult()

    /**
     * No contacts matched the spoken query.
     */
    data class NoMatch(
        val query: String
    ) : MatchResult()
}
