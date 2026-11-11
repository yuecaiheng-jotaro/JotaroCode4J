package org.jotaro.jotarocode.agent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只有方法上标了 Spring AI {@code @Tool} 的对象才会被收进会话。 */
class ToolObjectsTest {

    @Test
    void recognizesToolMethodsAndIgnoresPlainObjects() {
        assertTrue(ToolObjects.exposesTool(new ClockTool()));
        assertFalse(ToolObjects.exposesTool(new Object()));
    }
}
