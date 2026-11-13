package org.jotaro.jotarocode.agent.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentCatalogTest {

    @TempDir
    Path temp;

    @Test
    void builtinsCoverReadOnlyAndGeneralWork() {
        List<SubagentDefinition> agents = new SubagentCatalog().assemble(List.of());

        SubagentDefinition explore = agents.get(0);
        SubagentDefinition general = agents.get(1);
        assertEquals("explore", explore.name());
        assertEquals(List.of("read", "grep", "find", "ls"), explore.tools());
        assertEquals("general", general.name());
        assertNull(general.tools());
    }

    @Test
    void projectFileOverridesBuiltinAndAddsAgents() throws IOException {
        Path agentsDir = temp.resolve(".pi/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("explore.md"), """
                ---
                name: explore
                description: custom explorer
                tools: read
                ---
                look only
                """);
        Files.writeString(agentsDir.resolve("reviewer.md"), """
                ---
                name: reviewer
                description: reviews diffs
                tools: [read, grep]
                model: glm-4.6
                ---
                review carefully
                """);
        Files.writeString(agentsDir.resolve("broken.md"), """
                ---
                name: Bad Name
                description: skipped
                ---
                x
                """);
        Files.writeString(agentsDir.resolve("plain.md"), "no frontmatter\n");

        List<SubagentDefinition> agents = new SubagentCatalog().assemble(List.of(agentsDir));

        assertEquals(List.of("explore", "general", "reviewer"), agents.stream().map(SubagentDefinition::name).toList());
        assertEquals(List.of("read"), agents.get(0).tools());
        assertTrue(agents.get(0).systemPrompt().contains("look only"));
        SubagentDefinition reviewer = agents.get(2);
        assertEquals(List.of("read", "grep"), reviewer.tools());
        assertEquals("glm-4.6", reviewer.model());
        assertTrue(reviewer.systemPrompt().contains("review carefully"));
    }

    @Test
    void nearestProjectDirectoryStopsAtTheRepositoryRoot() throws IOException {
        Files.createDirectories(temp.resolve("outside/.pi/agents"));
        Path root = temp.resolve("repo");
        Files.createDirectories(root.resolve(".git"));
        Path projectAgents = root.resolve(".pi/agents");
        Files.createDirectories(projectAgents);
        Path nested = root.resolve("nested");
        Files.createDirectories(nested);

        List<Path> locations = SubagentCatalog.locations(nested);

        assertEquals(projectAgents.toAbsolutePath().normalize(), locations.get(locations.size() - 1).toAbsolutePath().normalize());
    }
}
