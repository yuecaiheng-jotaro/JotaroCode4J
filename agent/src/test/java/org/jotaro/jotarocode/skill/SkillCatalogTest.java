package org.jotaro.jotarocode.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用临时目录验证发现顺序、命令展开，以及搜索停在含 .git 的仓库根。 */
class SkillCatalogTest {

    @TempDir
    Path temp;

    @Test
    void advertisesSkillsByNameAndLoadsTheBodyOnCommand() throws Exception {
        Path root = temp.resolve("skills");
        Path pdf = root.resolve("pdf-tools");
        Files.createDirectories(pdf);
        Files.writeString(pdf.resolve("SKILL.md"), """
                ---
                name: pdf-tools
                description: Extract text from PDFs.
                ---

                # PDF

                Read references/formats.md.
                """);
        Files.createDirectories(root.resolve("manual-only"));
        Files.writeString(root.resolve("manual-only/SKILL.md"), """
                ---
                name: manual-only
                description: Only when the user names it.
                disable-model-invocation: true
                ---

                Secret steps.
                """);
        Files.createDirectories(root.resolve("nested/child-skill"));
        Files.writeString(root.resolve("nested/child-skill/SKILL.md"), """
                ---
                description: |
                  Nested skill.
                ---

                Nested body.
                """);

        SkillCatalog catalog = new SkillCatalog();
        List<Skill> skills = catalog.load(List.of(root));

        assertEquals(List.of("manual-only", "child-skill", "pdf-tools"), skills.stream().map(Skill::name).toList());
        String prompt = catalog.format(skills);
        assertTrue(prompt.contains("<name>pdf-tools</name>"));
        assertTrue(prompt.contains("<name>child-skill</name>"));
        assertFalse(prompt.contains("manual-only"));
        assertFalse(prompt.contains("Read references/formats.md."));

        String expanded = catalog.expand("/skill:pdf-tools extract report.pdf", skills);
        assertTrue(expanded.contains("Read references/formats.md."));
        assertTrue(expanded.endsWith("extract report.pdf"));
        assertTrue(catalog.expand("/skill:manual-only", skills).contains("Secret steps."));
        assertEquals("/skill:missing", catalog.expand("/skill:missing", skills));
    }

    @Test
    void projectSkillSearchStopsAtTheRepositoryRoot() throws Exception {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Path nested = repo.resolve("app/nested");
        Files.createDirectories(nested);

        List<Path> locations = SkillCatalog.locations(nested);

        assertTrue(locations.contains(nested.resolve(".agents/skills")));
        assertTrue(locations.contains(repo.resolve(".pi/skills")));
        assertFalse(locations.contains(temp.resolve(".agents/skills")));
    }
}
