package com.nova.callassist

import com.nova.callassist.contacts.ContactMatcher
import com.nova.callassist.contacts.ContactModel
import com.nova.callassist.contacts.MatchResult
import com.nova.callassist.contacts.PhoneNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContactMatcherTest {

    private lateinit var matcher: ContactMatcher
    private lateinit var sampleContacts: List<ContactModel>

    @Before
    fun setUp() {
        matcher = ContactMatcher()

        sampleContacts = listOf(
            ContactModel(
                id = "1",
                lookupKey = "key_1",
                displayName = "Arun Kumar",
                phoneNumbers = listOf(
                    PhoneNumber(number = "+91 98765 44821", type = "Mobile", isPrimary = true)
                )
            ),
            ContactModel(
                id = "2",
                lookupKey = "key_2",
                displayName = "Arun Prakash",
                phoneNumbers = listOf(
                    PhoneNumber(number = "+91 98765 49134", type = "Mobile", isPrimary = true)
                )
            ),
            ContactModel(
                id = "3",
                lookupKey = "key_3",
                displayName = "Priya Sharma",
                phoneNumbers = listOf(
                    PhoneNumber(number = "044 2345678", type = "Home", isPrimary = false),
                    PhoneNumber(number = "+91 91234 56789", type = "Mobile", isPrimary = true)
                )
            ),
            ContactModel(
                id = "4",
                lookupKey = "key_4",
                displayName = "Rahul",
                phoneNumbers = listOf(
                    PhoneNumber(number = "+91 99887 76655", type = "Mobile", isPrimary = true)
                )
            ),
            ContactModel(
                id = "5",
                lookupKey = "key_5",
                displayName = "Contact Without Number",
                phoneNumbers = emptyList()
            )
        )
    }

    @Test
    fun testExactFullNameMatching() {
        val result = matcher.match("Arun Kumar", sampleContacts)
        assertTrue(result is MatchResult.Single)
        val single = result as MatchResult.Single
        assertEquals("Arun Kumar", single.contact.displayName)
        assertEquals("+91 98765 44821", single.selectedNumber.number)
        assertEquals(1.0f, single.confidence, 0.01f)
    }

    @Test
    fun testCaseInsensitiveAndPunctuationNormalization() {
        val result = matcher.match("  pRiYa  sharma!! ", sampleContacts)
        assertTrue(result is MatchResult.Single)
        val single = result as MatchResult.Single
        assertEquals("Priya Sharma", single.contact.displayName)
    }

    @Test
    fun testAmbiguousMatchingForCommonPrefix() {
        // "Call Arun" should match BOTH "Arun Kumar" and "Arun Prakash"
        val result = matcher.match("Arun", sampleContacts)
        assertTrue("Expected Ambiguous match for 'Arun'", result is MatchResult.Ambiguous)
        val ambiguous = result as MatchResult.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
        val names = ambiguous.candidates.map { it.displayName }
        assertTrue(names.contains("Arun Kumar"))
        assertTrue(names.contains("Arun Prakash"))
    }

    @Test
    fun testSingleTokenExactMatch() {
        // Contact "Rahul" is an exact single-word contact
        val result = matcher.match("Rahul", sampleContacts)
        assertTrue(result is MatchResult.Single)
        val single = result as MatchResult.Single
        assertEquals("Rahul", single.contact.displayName)
    }

    @Test
    fun testMultiplePhoneNumbersSelectsPrimary() {
        val result = matcher.match("Priya Sharma", sampleContacts)
        assertTrue(result is MatchResult.Single)
        val single = result as MatchResult.Single
        // Priya has 2 phone numbers, Home and Mobile (primary)
        assertEquals(2, single.contact.phoneNumbers.size)
        assertEquals("+91 91234 56789", single.selectedNumber.number)
    }

    @Test
    fun testContactWithoutPhoneNumberReturnsNoPhoneNumber() {
        val result = matcher.match("Contact Without Number", sampleContacts)
        assertTrue(result is MatchResult.NoPhoneNumber)
        val noNumber = result as MatchResult.NoPhoneNumber
        assertEquals("Contact Without Number", noNumber.contact.displayName)
    }

    @Test
    fun testFuzzyMatchingForSpeechVariants() {
        // "Aron" should match "Arun" via Levenshtein distance
        val singleList = listOf(sampleContacts[0]) // Only Arun Kumar
        val result = matcher.match("Aron Kumar", singleList)
        assertTrue("Expected fuzzy match for 'Aron Kumar'", result is MatchResult.Single)
    }

    @Test
    fun testUnknownContactReturnsNoMatch() {
        val result = matcher.match("Siddharth", sampleContacts)
        assertTrue(result is MatchResult.NoMatch)
    }

    @Test
    fun testEmptyOrWhitespaceQueryReturnsNoMatch() {
        val result = matcher.match("   ", sampleContacts)
        assertTrue(result is MatchResult.NoMatch)
    }

    @Test
    fun testPhoneNumberMasking() {
        val phone = PhoneNumber("+919876544821", "Mobile")
        assertEquals("+919876544821", phone.dialableNumber)
        assertTrue(phone.maskedNumber.endsWith("4821"))
        assertTrue(phone.maskedNumber.contains("•"))
    }
}
