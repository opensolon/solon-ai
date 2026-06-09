package org.noear.solon.ai.sandbox.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ShellQuoteTest {

    // --- quoteArg ---

    @Test
    void quoteArg_simpleAlphaNumeric_noQuotes() {
        assertEquals("hello", ShellQuote.quoteArg("hello"));
    }

    @Test
    void quoteArg_pathWithSlashes_noQuotes() {
        assertEquals("/usr/bin/file", ShellQuote.quoteArg("/usr/bin/file"));
    }

    @Test
    void quoteArg_dashAndDot_noQuotes() {
        assertEquals("my-file.txt", ShellQuote.quoteArg("my-file.txt"));
    }

    @Test
    void quoteArg_safeChars_noQuotes() {
        assertEquals("a+b=c", ShellQuote.quoteArg("a+b=c"));
        assertEquals("a@b.com", ShellQuote.quoteArg("a@b.com"));
        assertEquals("100%", ShellQuote.quoteArg("100%"));
    }

    @Test
    void quoteArg_singleQuote_escaped() {
        assertEquals("'it'\"'\"'s'", ShellQuote.quoteArg("it's"));
    }

    @Test
    void quoteArg_dollarSign_quoted() {
        assertEquals("'$HOME'", ShellQuote.quoteArg("$HOME"));
    }

    @Test
    void quoteArg_emptyString_returnsEmptyQuotes() {
        assertEquals("''", ShellQuote.quoteArg(""));
    }

    @Test
    void quoteArg_null_returnsEmptyQuotes() {
        assertEquals("''", ShellQuote.quoteArg(null));
    }

    @Test
    void quoteArg_stringWithSpaces_quoted() {
        assertEquals("'hello world'", ShellQuote.quoteArg("hello world"));
    }

    @Test
    void quoteArg_stringWithBacktick_quoted() {
        assertEquals("'`whoami`'", ShellQuote.quoteArg("`whoami`"));
    }

    @Test
    void quoteArg_stringWithSemicolon_quoted() {
        assertEquals("'; rm -rf'", ShellQuote.quoteArg("; rm -rf"));
    }

    // --- quote ---

    @Test
    void quote_nullList_returnsEmpty() {
        assertEquals("", ShellQuote.quote(null));
    }

    @Test
    void quote_emptyList_returnsEmpty() {
        assertEquals("", ShellQuote.quote(Collections.emptyList()));
    }

    @Test
    void quote_singleSimpleArg_noQuotes() {
        assertEquals("hello", ShellQuote.quote(Arrays.asList("hello")));
    }

    @Test
    void quote_multipleArgs_spaceSeparated() {
        assertEquals("hello world", ShellQuote.quote(Arrays.asList("hello", "world")));
    }

    @Test
    void quote_mixedArgs_properlyQuoted() {
        assertEquals("echo '$HOME'", ShellQuote.quote(Arrays.asList("echo", "$HOME")));
    }

    @Test
    void quote_emptyArgInList_producesEmptyQuotes() {
        assertEquals("echo '' done", ShellQuote.quote(Arrays.asList("echo", "", "done")));
    }

    @Test
    void quote_argWithSpace_inSingleQuotes() {
        assertEquals("echo 'hello world'", ShellQuote.quote(Arrays.asList("echo", "hello world")));
    }
}
