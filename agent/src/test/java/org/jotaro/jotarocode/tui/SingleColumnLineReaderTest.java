package org.jotaro.jotarocode.tui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Candidate;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 宽终端上的短命令也要各自占一行，不能横着排进同一行。 */
class SingleColumnLineReaderTest {

    @Test
    void listsPrefixMatchesOnSeparateLines() throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .system(false)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();
        SingleColumnLineReader reader = new SingleColumnLineReader(terminal);
        AttributedStringBuilder displayed = new AttributedStringBuilder();
        reader.toColumns(
                List.of(new Candidate("/help"), new Candidate("/exit"), new Candidate("/model")),
                80,
                6,
                displayed,
                null,
                "",
                false,
                false,
                new int[2]);

        List<String> lines = displayed.toString().lines().filter(line -> !line.isBlank()).toList();
        assertEquals(List.of("/help", "/exit", "/model"), lines.stream().map(String::trim).toList());
    }

    @Test
    void upArrowRecallsSentLinesAndControlCInterrupts() throws Exception {
        SingleColumnLineReader reader = reader();
        reader.installInteractionKeys();
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);

        Binding up = main.getBound("\u001b[A");
        assertInstanceOf(Reference.class, up);
        assertEquals("jotaro-up", ((Reference) up).name());
        Binding down = main.getBound("\u001b[B");
        assertInstanceOf(Reference.class, down);
        assertEquals("jotaro-down", ((Reference) down).name());
        Binding enter = main.getBound("\n");
        assertInstanceOf(Reference.class, enter);
        assertEquals("jotaro-accept", ((Reference) enter).name());
        Binding interrupt = main.getBound(KeyMap.ctrl('C'));
        assertInstanceOf(Reference.class, interrupt);
        assertEquals("jotaro-interrupt", ((Reference) interrupt).name());
        Binding cancel = main.getBound("\u001b");
        assertInstanceOf(Reference.class, cancel);
        assertEquals("jotaro-cancel", ((Reference) cancel).name());
        Binding quit = main.getBound(KeyMap.ctrl('Z'));
        assertInstanceOf(Reference.class, quit);
        assertEquals("jotaro-quit", ((Reference) quit).name());

        KeyMap<Binding> menu = reader.getKeyMaps().get(LineReader.MENU);
        Binding menuDown = menu.getBound("\u001b[B");
        assertInstanceOf(Reference.class, menuDown);
        assertEquals(LineReader.DOWN_LINE_OR_HISTORY, ((Reference) menuDown).name());
        Binding menuEnter = menu.getBound("\n");
        assertInstanceOf(Reference.class, menuEnter);
        assertEquals("jotaro-menu-accept", ((Reference) menuEnter).name());
    }

    @Test
    void downAndEnterSelectsSlashCommand() throws Exception {
        String selected = read("/\u001b[B\n");
        assertEquals("/help", selected.trim());
    }

    @Test
    void downMovesToTheNextSlashCommand() throws Exception {
        String selected = read("/\u001b[B\u001b[B\n");
        assertEquals("/exit", selected.trim());
    }

    @Test
    void upSelectsTheLastSlashCommandThenAModel() throws Exception {
        String selected = read("/\u001b[A\n\u001b[B\n");
        assertEquals("/model deepseek-chat", selected.trim());
    }

    @Test
    void downAndEnterSelectsModelName() throws Exception {
        String selected = read("/model \u001b[B\n");
        assertEquals("/model deepseek-chat", selected.trim());
    }

    @Test
    void modelMenuKeepsEveryModelAndHighlightsOnlyTheSelection() throws Exception {
        SingleColumnLineReader reader = modelReader();
        type(reader, "/model deepseek-chat");

        assertTrue(reader.getWidgets().get("jotaro-down").apply());
        assertEquals("/model glm-4.6", reader.getBuffer().toString());
        assertMenu(reader.visibleMenu(), "glm-4.6");

        assertTrue(reader.getWidgets().get("jotaro-down").apply());
        assertEquals("/model qwen4-max-preview", reader.getBuffer().toString());
        assertMenu(reader.visibleMenu(), "qwen4-max-preview");

        type(reader, "/model deepseek-chat");
        assertTrue(reader.getWidgets().get("jotaro-up").apply());
        assertEquals("/model qwen4-max-preview", reader.getBuffer().toString());
        assertMenu(reader.visibleMenu(), "qwen4-max-preview");
    }

    @Test
    void completerAutosuggestionDoesNotCollapseTheModelMenu() throws Exception {
        Terminal terminal = new DumbTerminal(
                "test",
                "xterm-256color",
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                StandardCharsets.UTF_8);
        terminal.setSize(new org.jline.terminal.Size(80, 24));
        SingleColumnLineReader reader = new SingleColumnLineReader(terminal);
        reader.setCompleter(new SlashCommandCompleter(() -> MODELS));
        reader.installInteractionKeys();
        reader.setAutosuggestion(LineReader.SuggestionType.COMPLETER);
        type(reader, "/model deepseek-chat");

        assertTrue(reader.getWidgets().get("jotaro-down").apply());

        List<String> lines = reader.paint().toString().lines().toList();
        assertTrue(lines.contains("deepseek-chat"), lines.toString());
        assertTrue(lines.contains("glm-4.6"), lines.toString());
        assertTrue(lines.contains("qwen4-max-preview"), lines.toString());
    }

    @Test
    void escapeAndControlCCancelAnySlashList() throws Exception {
        SingleColumnLineReader reader = colorModelReader();
        type(reader, "/model ");
        assertTrue(reader.getWidgets().get("jotaro-down").apply());
        assertMenu(reader.visibleMenu(), "deepseek-chat");

        assertTrue(reader.getWidgets().get("jotaro-cancel").apply());
        assertEquals("/model ", reader.getBuffer().toString());
        assertNull(reader.visibleMenu());
        assertFalse(reader.interrupted());
        assertModelListHidden(reader.displayed());

        type(reader, "/model deepseek-chat");
        assertTrue(reader.getWidgets().get("jotaro-down").apply());
        assertTrue(reader.getWidgets().get("jotaro-down").apply());
        assertEquals("/model qwen4-max-preview", reader.getBuffer().toString());
        assertTrue(reader.getWidgets().get("jotaro-interrupt").apply());
        assertEquals("/model deepseek-chat", reader.getBuffer().toString());
        assertNull(reader.visibleMenu());
        assertFalse(reader.interrupted());
        assertModelListHidden(reader.displayed());

        type(reader, "/");
        assertTrue(reader.getWidgets().get("jotaro-down").apply());
        assertEquals("/help", reader.getBuffer().toString());
        assertTrue(reader.visibleMenu().toString().contains("/exit"));
        assertTrue(reader.getWidgets().get("jotaro-cancel").apply());
        assertEquals("/", reader.getBuffer().toString());
        assertNull(reader.visibleMenu());
        assertFalse(reader.interrupted());
        assertFalse(reader.displayed().toString().contains("/exit"));

        type(reader, "/mo");
        assertTrue(reader.getWidgets().get("jotaro-down").apply());
        assertEquals("/model", reader.getBuffer().toString());
        assertTrue(reader.getWidgets().get("jotaro-interrupt").apply());
        assertEquals("/mo", reader.getBuffer().toString());
        assertNull(reader.visibleMenu());
        assertFalse(reader.interrupted());

        type(reader, "/model deepseek-chat");
        assertTrue(reader.getWidgets().get("jotaro-cancel").apply());
        assertEquals("/model deepseek-chat", reader.getBuffer().toString());

        SingleColumnLineReader plain = modelReader();
        type(plain, "hello");
        assertTrue(plain.getWidgets().get("jotaro-interrupt").apply());
        assertTrue(plain.interrupted());
        assertEquals("hello", plain.getBuffer().toString());
    }

    @Test
    void escapeAndControlCDuringReadRestoreTheDraft() throws Exception {
        assertEquals("/model z", readKeys("/model \u001b[B", "\u001b", "z\n"));
        assertEquals("/model z", readKeys("/model \u001b[B", "\u0003", "z\n"));
        assertEquals("/ z", readKeys("/", "\u001b[B", "\u001b", " z\n"));
    }

    @Test
    void emptyModelArgumentListsEveryModelWithTheFirstHighlighted() throws Exception {
        SingleColumnLineReader reader = modelReader();
        type(reader, "/model ");

        assertTrue(reader.getWidgets().get("jotaro-down").apply());

        assertEquals("/model deepseek-chat", reader.getBuffer().toString());
        assertMenu(reader.visibleMenu(), "deepseek-chat");
    }

    @Test
    void typedModelPrefixStillListsEveryModel() throws Exception {
        SingleColumnLineReader reader = modelReader();
        type(reader, "/model q");

        assertTrue(reader.getWidgets().get("jotaro-down").apply());

        assertEquals("/model qwen4-max-preview", reader.getBuffer().toString());
        assertMenu(reader.visibleMenu(), "qwen4-max-preview");
    }

    @Test
    void upOnPlainTextRecallsSentLine() throws Exception {
        String recalled = read("\u001b[A\n", "看一下 README");
        assertEquals("看一下 README", recalled);
    }

    @Test
    void upOnFinishedSlashCommandRecallsSentLine() throws Exception {
        String recalled = read("/help\u001b[A\n", "看一下 README");
        assertEquals("看一下 README", recalled);
    }

    @Test
    void slashCommandsStayOutOfSentHistory() throws Exception {
        SingleColumnLineReader reader = reader();
        reader.installInteractionKeys();
        DefaultHistory history = new DefaultHistory();
        reader.setHistory(history);
        history.attach(reader);
        history.add(Instant.now(), "/help");
        history.add(Instant.now(), "看一下 README");

        List<String> lines = new ArrayList<>();
        for (History.Entry entry : history) {
            lines.add(entry.line());
        }
        assertEquals(List.of("看一下 README"), lines);
    }

    private static String readKeys(String... steps) throws Exception {
        PipedOutputStream output = new PipedOutputStream();
        PipedInputStream input = new PipedInputStream(output, 1024);
        Thread writer = new Thread(() -> {
            try {
                for (String step : steps) {
                    output.write(step.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    Thread.sleep(120);
                }
                output.close();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        writer.start();
        Terminal terminal = new DumbTerminal(
                "test",
                "dumb",
                input,
                new ByteArrayOutputStream(),
                StandardCharsets.UTF_8);
        terminal.setSize(new org.jline.terminal.Size(80, 24));
        SingleColumnLineReader reader = new SingleColumnLineReader(terminal);
        reader.setCompleter(new SlashCommandCompleter(() -> MODELS));
        reader.installInteractionKeys();
        String line = reader.readLine("jotaro> ");
        writer.join(2000);
        return line.trim();
    }

    private static String read(String keys, String... historyLines) throws Exception {
        Terminal terminal = new DumbTerminal(
                "test",
                "dumb",
                new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(),
                StandardCharsets.UTF_8);
        terminal.setSize(new org.jline.terminal.Size(80, 24));
        SingleColumnLineReader reader = new SingleColumnLineReader(terminal);
        reader.setCompleter(new SlashCommandCompleter(() -> List.of("glm-4.6", "deepseek-chat")));
        reader.installInteractionKeys();
        DefaultHistory history = new DefaultHistory();
        reader.setHistory(history);
        history.attach(reader);
        for (String line : historyLines) {
            history.add(Instant.now(), line);
        }
        return reader.readLine("jotaro> ");
    }

    private static final List<String> MODELS = List.of("glm-4.6", "deepseek-chat", "qwen4-max-preview");

    private static void assertModelListHidden(AttributedString displayed) {
        List<String> lines = displayed.toString().lines().toList();
        assertFalse(lines.contains("deepseek-chat"), lines.toString());
        assertFalse(lines.contains("glm-4.6"), lines.toString());
        assertFalse(lines.contains("qwen4-max-preview"), lines.toString());
    }

    private static SingleColumnLineReader colorModelReader() throws Exception {
        Terminal terminal = new DumbTerminal(
                "test",
                "xterm-256color",
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                StandardCharsets.UTF_8);
        terminal.setSize(new org.jline.terminal.Size(80, 24));
        SingleColumnLineReader reader = new SingleColumnLineReader(terminal);
        reader.setCompleter(new SlashCommandCompleter(() -> MODELS));
        reader.installInteractionKeys();
        reader.setAutosuggestion(LineReader.SuggestionType.COMPLETER);
        return reader;
    }

    private static SingleColumnLineReader modelReader() throws Exception {
        SingleColumnLineReader reader = reader();
        reader.setCompleter(new SlashCommandCompleter(() -> MODELS));
        reader.installInteractionKeys();
        return reader;
    }

    private static void type(SingleColumnLineReader reader, String line) {
        reader.getBuffer().clear();
        reader.getBuffer().write(line);
        reader.getBuffer().cursor(line.length());
    }

    private static void assertMenu(AttributedString menu, String highlighted) {
        assertEquals(List.of("deepseek-chat", "glm-4.6", "qwen4-max-preview"), menu.toString().lines().toList());
        long highlight = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold().getStyle();
        assertEquals(highlight, menu.styleAt(menu.toString().indexOf(highlighted)).getStyle());
        for (String other : List.of("deepseek-chat", "glm-4.6", "qwen4-max-preview")) {
            if (other.equals(highlighted)) {
                continue;
            }
            assertEquals(AttributedStyle.DEFAULT.getStyle(), menu.styleAt(menu.toString().indexOf(other)).getStyle());
        }
    }

    private static SingleColumnLineReader reader() throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .system(false)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();
        return new SingleColumnLineReader(terminal);
    }
}
