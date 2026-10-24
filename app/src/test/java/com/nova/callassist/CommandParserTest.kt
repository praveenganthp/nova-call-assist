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
