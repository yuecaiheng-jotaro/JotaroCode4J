package com.kama.piagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class PiAgentApplicationTest {

    @Test
    void tuiFlagOverridesConfiguredServerMode() {
        assertArrayEquals(
                new String[]{"--tui", "--piagent.mode=tui"},
                PiAgentApplication.ensureTuiMode(new String[]{"--tui"}));
    }

    @Test
    void explicitTuiModeIsNotDuplicated() {
        String[] args = {"--piagent.mode=tui"};
        assertArrayEquals(args, PiAgentApplication.ensureTuiMode(args));
    }
}
