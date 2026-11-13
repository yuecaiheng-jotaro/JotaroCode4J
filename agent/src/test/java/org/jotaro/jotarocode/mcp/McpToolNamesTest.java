package org.jotaro.jotarocode.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolNamesTest {

    @Test
    void prefixesTheServerNameAndKeepsBuiltinNamesFree() {
        assertEquals("filesystem_list_dir", McpToolNames.join("filesystem", "list-dir"));
        assertEquals("mcp_read", McpToolNames.join(" ", "read"));
        assertTrue(McpToolNames.builtin("bash"));
        assertFalse(McpToolNames.builtin("filesystem_list_dir"));
        assertTrue(McpToolNames.join("s".repeat(80), "t".repeat(80)).length() <= 64);
        assertTrue(Character.isLetter(McpToolNames.join("9server", "1tool").charAt(0)));
    }
}
