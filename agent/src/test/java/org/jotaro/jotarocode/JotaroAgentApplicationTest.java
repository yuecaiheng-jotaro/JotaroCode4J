package org.jotaro.jotarocode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** 校验 --tui 会改成无 Web，并在参数末尾补上 --jotaro.mode=tui。 */
class JotaroAgentApplicationTest {

    @Test
    void tuiFlagOverridesConfiguredServerMode() {
        assertArrayEquals(
                new String[]{"--tui", "--jotaro.mode=tui"},
                JotaroAgentApplication.ensureTuiMode(new String[]{"--tui"}));
    }

    @Test
    void explicitTuiModeIsNotDuplicated() {
        String[] args = {"--jotaro.mode=tui"};
        assertArrayEquals(args, JotaroAgentApplication.ensureTuiMode(args));
    }
}
