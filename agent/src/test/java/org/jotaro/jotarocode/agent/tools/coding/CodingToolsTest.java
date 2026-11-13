package org.jotaro.jotarocode.agent.tools.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 在临时目录里读写、搜索、列目录，并跑一条成功和一条失败的 shell。 */
class CodingToolsTest {

    @TempDir
    Path root;

    @Test
    void exposesTheEightNativeToolNames() {
        List<Object> tools = List.of(
                new ReadTool(),
                new BashTool(),
                new EditTool(),
                new WriteTool(),
                new GrepTool(),
                new FindTool(),
                new LsTool(),
                new PowerShellTool()
        );
        Set<String> names = tools.stream()
                .flatMap(tool -> Stream.of(MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks()))
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertEquals(Set.of("read", "bash", "edit", "write", "grep", "find", "ls", "powershell"), names);
    }

    @Test
    void readsWritesAndEditsText() throws Exception {
        Path file = root.resolve("notes.txt");
        WriteTool writeTool = new WriteTool();
        ReadTool readTool = new ReadTool();
        EditTool editTool = new EditTool();

        assertEquals("Successfully wrote to " + file, writeTool.write(file.toString(), "one\ntwo\nthree\n"));
        assertEquals("two", readTool.read(file.toString(), 2, 1).lines().findFirst().orElseThrow());
        assertTrue(readTool.read(file.toString(), 2, 1).contains("offset=3"));

        assertEquals("Successfully replaced 1 block(s) in " + file + ".",
                editTool.edit(file.toString(), List.of(new EditTool.TextEdit("two", "TWO"))));
        assertTrue(readTool.read(file.toString(), null, null).contains("TWO"));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> editTool.edit(file.toString(), List.of(new EditTool.TextEdit("e", "E"))));
        assertTrue(duplicate.getMessage().contains("unique"));

        Path nested = root.resolve("nested/leaf.txt");
        writeTool.write(nested.toString(), "leaf");
        assertEquals("leaf", readTool.read(nested.toString(), null, null));
    }

    @Test
    void listsDirectoriesAndSearchesAroundGitignore() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "secret.txt\nskipped/\n");
        Files.writeString(root.resolve(".hidden"), "dot");
        Files.writeString(root.resolve("note.txt"), "hello visible\n");
        Files.writeString(root.resolve("secret.txt"), "hello secret\n");
        Files.createDirectories(root.resolve("skipped"));
        Files.writeString(root.resolve("skipped/hidden.txt"), "hello hidden\n");
        Files.createDirectories(root.resolve("src/nested"));
        Files.writeString(root.resolve("src/A.java"), "class A {}\n");
        Files.writeString(root.resolve("src/nested/B.java"), "class B {}\n");
        Files.createDirectories(root.resolve("dir"));
        Files.writeString(root.resolve("dir/child.txt"), "hello child\n");
        Files.writeString(root.resolve("ctx.txt"), "a\nb\nhit\nc\nd\n");

        String listed = new LsTool().ls(root.toString(), null);
        assertTrue(listed.contains(".hidden"));
        assertTrue(listed.contains("dir/"));
        assertTrue(listed.contains("note.txt"));

        String found = new FindTool().find("src/**/*.java", root.toString(), null);
        assertTrue(found.contains("src/A.java"));
        assertTrue(found.contains("src/nested/B.java"));
        String texts = new FindTool().find("*.txt", root.toString(), null);
        assertTrue(texts.contains("note.txt"));
        assertFalse(texts.contains("secret.txt"));
        assertFalse(texts.contains("hidden.txt"));

        String matches = new GrepTool().grep("hello", root.toString(), null, null, null, null, null);
        assertTrue(matches.contains("note.txt:1: hello visible"));
        assertTrue(matches.contains("dir/child.txt:1: hello child"));
        assertFalse(matches.contains("secret"));
        assertFalse(matches.contains("hidden"));

        String context = new GrepTool().grep("hit", root.toString(), "ctx.txt", null, true, 1, null);
        assertTrue(context.contains("ctx.txt-2- b"));
        assertTrue(context.contains("ctx.txt:3: hit"));
        assertTrue(context.contains("ctx.txt-4- c"));
    }

    @Test
    void describesImagesWithoutInliningThem() throws Exception {
        Path image = root.resolve("photo.png");
        Files.writeString(image, "not really an image");
        String note = new ReadTool().read(image.toString(), null, null);
        assertTrue(note.contains("image/png"));
        assertTrue(note.contains("does not support images"));
    }

    @Test
    void bashReturnsOutputAndFailure() {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")));
        BashTool bash = new BashTool();
        assertTrue(bash.bash("printf 'hello-jotaro'", 10).contains("hello-jotaro"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> bash.bash("exit 3", 10));
        assertTrue(failure.getMessage().contains("Command exited with code 3"));
    }

    @Test
    void powershellRunsWhenInstalled() {
        PowerShellTool powershell = new PowerShellTool();
        try {
            assertTrue(powershell.powershell("Write-Output hi-ps", 20).contains("hi-ps"));
        } catch (IllegalStateException exception) {
            assumeFalse(exception.getMessage().contains("not available"));
            throw exception;
        }
    }
}
