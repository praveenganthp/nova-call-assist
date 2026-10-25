package com.nova.callassist

import com.nova.callassist.assistant.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandParserTest {

    private lateinit var parser: CommandParser

    @Before
    fun setUp() {
        parser = CommandParser()
    }

    @Test
    fun testStandardCallCommands() {
        val cmd1 = parser.parse("Call Arun")
        assertTrue(cmd1 is CommandParser.Command.Call)
        assertEquals("arun", (cmd1 as CommandParser.Command.Call).targetName)

        val cmd2 = parser.parse("Please call Priya")
        assertTrue(cmd2 is CommandParser.Command.Call)
        assertEquals("priya", (cmd2 as CommandParser.Command.Call).targetName)

        val cmd3 = parser.parse("Call Rahul Kumar.")
        assertTrue(cmd3 is CommandParser.Command.Call)
        assertEquals("rahul kumar", (cmd3 as CommandParser.Command.Call).targetName)

        val cmd4 = parser.parse("Make a call to my friend Arun")
        assertTrue(cmd4 is CommandParser.Command.Call)
        assertEquals("arun", (cmd4 as CommandParser.Command.Call).targetName)

        val cmd5 = parser.parse("Dial 9876543210")
        assertTrue(cmd5 is CommandParser.Command.Call)
        assertEquals("9876543210", (cmd5 as CommandParser.Command.Call).targetName)
    }

    @Test
    fun testConversationalAndTargetNameExtraction() {
        // "Call Arun", "Call Praveen", "Call Vignesh"
        val c1 = parser.parse("Call Arun")
        assertTrue(c1 is CommandParser.Command.Call)
        assertEquals("arun", (c1 as CommandParser.Command.Call).targetName)

        val c2 = parser.parse("Call Praveen")
        assertTrue(c2 is CommandParser.Command.Call)
        assertEquals("praveen", (c2 as CommandParser.Command.Call).targetName)

        val c3 = parser.parse("Call Vignesh")
        assertTrue(c3 is CommandParser.Command.Call)
        assertEquals("vignesh", (c3 as CommandParser.Command.Call).targetName)

        // Conversational variations
        val c4 = parser.parse("Please call Arun")
        assertTrue(c4 is CommandParser.Command.Call)
        assertEquals("arun", (c4 as CommandParser.Command.Call).targetName)

        val c5 = parser.parse("Can you call Praveen?")
        assertTrue(c5 is CommandParser.Command.Call)
        assertEquals("praveen", (c5 as CommandParser.Command.Call).targetName)

        val c6 = parser.parse("Could you please call Vignesh?")
        assertTrue(c6 is CommandParser.Command.Call)
        assertEquals("vignesh", (c6 as CommandParser.Command.Call).targetName)

        val c7 = parser.parse("Call Arun please.")
        assertTrue(c7 is CommandParser.Command.Call)
        assertEquals("arun", (c7 as CommandParser.Command.Call).targetName)

        val c8 = parser.parse("Hey Nova, call Praveen")
        assertTrue(c8 is CommandParser.Command.Call)
        assertEquals("praveen", (c8 as CommandParser.Command.Call).targetName)
    }

    @Test
    fun testPunctuationAndCapitalizationDifferences() {
        val c1 = parser.parse("CALL ARUN!!!")
        assertTrue(c1 is CommandParser.Command.Call)
        assertEquals("arun", (c1 as CommandParser.Command.Call).targetName)

        val c2 = parser.parse("call praveen...")
        assertTrue(c2 is CommandParser.Command.Call)
        assertEquals("praveen", (c2 as CommandParser.Command.Call).targetName)

        val c3 = parser.parse("   cAlL   ViGnEsH?   ")
        assertTrue(c3 is CommandParser.Command.Call)
        assertEquals("vignesh", (c3 as CommandParser.Command.Call).targetName)
    }

    @Test
    fun testTranscriptContainingOnlyContactName() {
        val c1 = parser.parse("Arun")
        assertTrue(c1 is CommandParser.Command.Call)
        assertEquals("arun", (c1 as CommandParser.Command.Call).targetName)

        val c2 = parser.parse("Praveen")
        assertTrue(c2 is CommandParser.Command.Call)
        assertEquals("praveen", (c2 as CommandParser.Command.Call).targetName)

        val c3 = parser.parse("Vignesh")
        assertTrue(c3 is CommandParser.Command.Call)
        assertEquals("vignesh", (c3 as CommandParser.Command.Call).targetName)
    }

    @Test
    fun testWrongOrIncompleteTranscript() {
        // Incomplete command words without a name
        assertTrue(parser.parse("call") is CommandParser.Command.Unknown)
        assertTrue(parser.parse("please call") is CommandParser.Command.Unknown)
        assertTrue(parser.parse("call to") is CommandParser.Command.Unknown)

        // Wake words alone without a command
        assertTrue(parser.parse("hey nova") is CommandParser.Command.Unknown)
        assertTrue(parser.parse("nova") is CommandParser.Command.Unknown)

        // Irrelevant phrases
        assertTrue(parser.parse("what is the weather") is CommandParser.Command.Unknown)
        assertTrue(parser.parse("open camera") is CommandParser.Command.Unknown)
    }

    @Test
    fun testConfirmationCommands() {
        assertTrue(parser.parse("yes") is CommandParser.Command.Confirm)
        assertTrue(parser.parse("confirm") is CommandParser.Command.Confirm)
        assertTrue(parser.parse("call now") is CommandParser.Command.Confirm)
        assertTrue(parser.parse("go ahead") is CommandParser.Command.Confirm)
        assertTrue(parser.parse("okay") is CommandParser.Command.Confirm)
        assertTrue(parser.parse("sure!") is CommandParser.Command.Confirm)
    }

    @Test
    fun testCancellationCommands() {
        assertTrue(parser.parse("cancel") is CommandParser.Command.Cancel)
        assertTrue(parser.parse("stop") is CommandParser.Command.Cancel)
        assertTrue(parser.parse("stop listening") is CommandParser.Command.Cancel)
        assertTrue(parser.parse("no") is CommandParser.Command.Cancel)
        assertTrue(parser.parse("don't call") is CommandParser.Command.Cancel)
        assertTrue(parser.parse("never mind") is CommandParser.Command.Cancel)
        assertTrue(parser.parse("abort") is CommandParser.Command.Cancel)
    }

    @Test
    fun testRepeatCommands() {
        assertTrue(parser.parse("repeat") is CommandParser.Command.Repeat)
        assertTrue(parser.parse("repeat the name") is CommandParser.Command.Repeat)
        assertTrue(parser.parse("say again") is CommandParser.Command.Repeat)
        assertTrue(parser.parse("who is it") is CommandParser.Command.Repeat)
    }

    @Test
    fun testOptionSelectionCommands() {
        val cmd1 = parser.parse("option 1")
        assertTrue(cmd1 is CommandParser.Command.SelectOption)
        assertEquals(1, (cmd1 as CommandParser.Command.SelectOption).index)

        val cmd2 = parser.parse("number two")
        assertTrue(cmd2 is CommandParser.Command.SelectOption)
        assertEquals(2, (cmd2 as CommandParser.Command.SelectOption).index)

        val cmd3 = parser.parse("first one")
        assertTrue(cmd3 is CommandParser.Command.SelectOption)
        assertEquals(1, (cmd3 as CommandParser.Command.SelectOption).index)

        val cmd4 = parser.parse("second")
        assertTrue(cmd4 is CommandParser.Command.SelectOption)
        assertEquals(2, (cmd4 as CommandParser.Command.SelectOption).index)
    }

    @Test
    fun testDirectSpokenNameFallback() {
        // When asked "Who would you like to call?" user may just say "Arun Kumar"
        val cmd = parser.parse("Arun Kumar")
        assertTrue(cmd is CommandParser.Command.Call)
        assertEquals("arun kumar", (cmd as CommandParser.Command.Call).targetName)
    }

    @Test
    fun testEmptyOrInvalidTranscript() {
        assertTrue(parser.parse("") is CommandParser.Command.Unknown)
        assertTrue(parser.parse("   ") is CommandParser.Command.Unknown)
        assertTrue(parser.parse("...") is CommandParser.Command.Unknown)
    }
}
