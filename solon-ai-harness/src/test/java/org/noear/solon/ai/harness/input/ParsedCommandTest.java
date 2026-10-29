/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.input;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令解析口径
 */
class ParsedCommandTest {

    @Test
    void nonSlashInputIsNotCommand() {
        assertNull(ParsedCommand.parse("hello world"));
        assertNull(ParsedCommand.parse(""));
        assertNull(ParsedCommand.parse(null));
    }

    @Test
    void loneSlashIsNotCommandInsteadOfThrowing() {
        //历史隐患：CLI 直接 parts.get(0)，输入单个 / 会 IndexOutOfBounds
        assertNull(ParsedCommand.parse("/"));
        assertNull(ParsedCommand.parse("/   "));
    }

    @Test
    void nameIsLowercasedAndArgsSplit() {
        ParsedCommand cmd = ParsedCommand.parse("/CLEAR now please");
        assertEquals("clear", cmd.getName());
        assertEquals(Arrays.asList("now", "please"), cmd.getArgs());
        assertEquals(2, cmd.argCount());
        assertEquals("now please", cmd.getArgsJoined());
        assertEquals("/CLEAR now please", cmd.getRawInput());
    }

    @Test
    void noArgsYieldsEmptyList() {
        ParsedCommand cmd = ParsedCommand.parse("/help");
        assertEquals("help", cmd.getName());
        assertTrue(cmd.getArgs().isEmpty());
        assertNull(cmd.argAt(0));
    }

    @Test
    void quotedArgsStayTogether() {
        ParsedCommand cmd = ParsedCommand.parse("/model \"gpt 4o\"");
        assertEquals("model", cmd.getName());
        assertEquals(1, cmd.argCount());
    }
}
