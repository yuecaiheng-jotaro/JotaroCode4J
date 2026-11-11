package org.jotaro.jotarocode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** 校验 --tui 会改成无 Web，并在参数末尾补上 --piagent.mode=tui。 */
class JotaroAgentApplicationTest {

    @Test
    void tuiFlagOverridesConfiguredServerMode() {
        assertArrayEquals(
                new String[]{"--tui", "--piagent.mode=tui"},
                JotaroAgentApplication.ensureTuiMode(new String[]{"--tui"}));
    }

    @Test
    void explicitTuiModeIsNotDuplicated() {
        String[] args = {"--piagent.mode=tui"};
        assertArrayEquals(args, JotaroAgentApplication.ensureTuiMode(args));
    }
}
