package org.jotaro.jotarocode.tui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.MaskingCallback;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * 补全候选按一列往下排。JLine 默认按终端宽度把短名字横着铺开，
 * {@code /help /exit /model} 会落在同一行。
 * 行首是 {@code /} 时，上下键在这些候选里移动，回车选定；其他文字仍用上下键回溯已发送的内容。
 * 任何斜杠命令列出候选时，三个或更多条目都保持可见，只有当前项高亮。
 */
final class SingleColumnLineReader extends LineReaderImpl {

    private List<Candidate> slashChoices = List.of();
    private int slashIndex = -1;
    private String slashDraft = "";
    private int slashDraftCursor;
    private String footerText = "";

    SingleColumnLineReader(Terminal terminal) {
        super(terminal, "pi-agent", new HashMap<>());
    }

    /**
     * 普通行：上键回溯已接受的行，下键再往回走。方向键默认是按前缀搜索，空行经常什么都不出现。
     * 斜杠行：上下键在当前候选里移动并高亮，回车选定。{@code /model} 选定后先补上空格，方便接着选模型名。
     * 模型名写进输入后不再按这个全名收窄列表，上下键始终在全部模型之间移动。
     * 已经填完整、没有后续参数的斜杠命令，上下键仍然回溯历史。
     * 候选列表开着时，Esc 或 Ctrl+C 取消这次选择，把输入恢复成列表打开前的内容，并收起列表。
     * 没有候选列表时，Ctrl+C 仍记成中断，交给外层停掉当前运行。Ctrl+Z 直接结束进程。
     * 以 {@code /} 开头的命令不进历史。
     */
    void installInteractionKeys() {
        getWidgets().put("jotaro-interrupt", this::interruptLine);
        getWidgets().put("jotaro-quit", this::forceQuit);
        getWidgets().put("jotaro-cancel", this::cancelSlashSelection);
        getWidgets().put("jotaro-up", () -> moveSlash(false));
        getWidgets().put("jotaro-down", () -> moveSlash(true));
        getWidgets().put("jotaro-accept", this::acceptSlash);
        bindEditing(LineReader.MAIN);
        bindEditing(LineReader.EMACS);
        bindMenu();
        for (KeyMap<Binding> map : getKeyMaps().values()) {
            if (map == null) {
                continue;
            }
            // Esc 同时也是方向键的前缀，超时太长会让取消看起来没生效。
            map.setAmbiguousTimeout(50);
            bind(map, "jotaro-cancel", "\u001b");
            map.bind(new Reference("jotaro-interrupt"), KeyMap.ctrl('C'));
            map.bind(new Reference("jotaro-quit"), KeyMap.ctrl('Z'));
        }
        setVariable(LineReader.HISTORY_IGNORE, "/*");
    }

    /**
     * 原始模式仍开着 ISIG，Ctrl+C 会直接变成中断信号，读行被抛出，候选列表来不及收起。
     * 读的时候先关掉信号，让 Ctrl+C 作为按键进到取消或中断逻辑，读完再恢复。
     */
    @Override
    public String readLine(String prompt, String rightPrompt, MaskingCallback maskingCallback, String buffer)
            throws UserInterruptException, EndOfFileException {
        boolean disabled = disableInterruptSignal();
        restoreFooter();
        try {
            return super.readLine(prompt, rightPrompt, maskingCallback, buffer);
        } finally {
            if (disabled) {
                enableInterruptSignal();
            }
        }
    }

    private boolean disableInterruptSignal() {
        return setInterruptSignal(false);
    }

    private void enableInterruptSignal() {
        setInterruptSignal(true);
    }

    private boolean setInterruptSignal(boolean enabled) {
        try {
            Attributes attributes = terminal.getAttributes();
            if (attributes == null || attributes.getLocalFlag(Attributes.LocalFlag.ISIG) == enabled) {
                return false;
            }
            attributes.setLocalFlag(Attributes.LocalFlag.ISIG, enabled);
            terminal.setAttributes(attributes);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 读行时关掉了信号，Ctrl+Z 不再由终端挂起进程。这里先把终端交还，再强制结束。
     */
    private boolean forceQuit() {
        restoreTerminal();
        System.exit(0);
        return true;
    }

    private void restoreTerminal() {
        try {
            Attributes attributes = terminal.getAttributes();
            if (attributes == null) {
                return;
            }
            attributes.setLocalFlag(Attributes.LocalFlag.ISIG, true);
            attributes.setLocalFlag(Attributes.LocalFlag.ICANON, true);
            attributes.setLocalFlag(Attributes.LocalFlag.ECHO, true);
            attributes.setLocalFlag(Attributes.LocalFlag.IEXTEN, true);
            attributes.setInputFlag(Attributes.InputFlag.IXON, true);
            attributes.setInputFlag(Attributes.InputFlag.ICRNL, true);
            terminal.setAttributes(attributes);
            terminal.writer().write("\r\n");
            terminal.flush();
        } catch (RuntimeException exception) {
            // 恢复失败也要退出，避免停在原始模式里
        }
    }

    private boolean interruptLine() {
        if (slashIndex >= 0) {
            cancelSlashSelection();
            return true;
        }
        state = State.INTERRUPT;
        return true;
    }

    /**
     * 收起任意斜杠命令的候选列表，并把输入恢复成列表打开前的内容。
     * 同时停掉这一轮补全重列，避免列表刚收起又被刷回来。
     */
    private boolean cancelSlashSelection() {
        if (slashIndex >= 0) {
            restoreDraft();
        }
        clearSlashList();
        doAutosuggestion = false;
        eraseTail();
        return true;
    }

    private void eraseTail() {
        try {
            terminal.writer().write("\u001b[J");
            terminal.flush();
        } catch (RuntimeException exception) {
            // 刷新失败时仍靠随后的 redisplay 覆盖旧列表
        }
    }

    private void rememberDraft() {
        slashDraft = buf.toString();
        slashDraftCursor = buf.cursor();
    }

    private void restoreDraft() {
        buf.clear();
        buf.write(slashDraft);
        buf.cursor(Math.min(Math.max(slashDraftCursor, 0), buf.length()));
    }

    boolean interrupted() {
        return state == State.INTERRUPT;
    }

    private boolean moveSlash(boolean down) {
        if (!slashBuffer() || completer == null) {
            clearSlashList();
            return history(down);
        }
        ParsedLine parsed = currentLine();
        if (modelArgument(parsed)) {
            return moveModel(parsed, down);
        }
        if (menuActive(parsed)) {
            int size = slashChoices.size();
            slashIndex = down ? (slashIndex + 1) % size : (slashIndex - 1 + size) % size;
            Candidate chosen = slashChoices.get(slashIndex);
            writeCandidate(chosen, parsed, false);
            showList(chosen);
            return true;
        }
        List<Candidate> matches = matches(parsed);
        if (matches.isEmpty() || alreadyChosen(matches, parsed)) {
            clearSlashList();
            return history(down);
        }
        rememberDraft();
        slashChoices = List.copyOf(matches);
        slashIndex = down ? 0 : slashChoices.size() - 1;
        Candidate chosen = slashChoices.get(slashIndex);
        writeCandidate(chosen, parsed, false);
        showList(chosen);
        return true;
    }

    private boolean acceptSlash() {
        if (!slashBuffer() || completer == null) {
            clearSlashList();
            return acceptLine();
        }
        ParsedLine parsed = currentLine();
        if (menuActive(parsed)) {
            return acceptChosen(slashChoices.get(slashIndex), parsed);
        }
        List<Candidate> matches = matches(parsed);
        if (matches.isEmpty()) {
            clearSlashList();
            return acceptLine();
        }
        Candidate exact = exact(matches, parsed.word());
        if (exact != null) {
            return acceptChosen(exact, parsed);
        }
        if (matches.size() == 1) {
            Candidate chosen = matches.get(0);
            writeCandidate(chosen, parsed, true);
            clearSlashList();
            if (chosen.suffix() != null && !chosen.suffix().isEmpty()) {
                return true;
            }
            return acceptLine();
        }
        rememberDraft();
        slashChoices = List.copyOf(matches);
        slashIndex = 0;
        Candidate chosen = slashChoices.get(0);
        writeCandidate(chosen, parsed, false);
        showList(chosen);
        return true;
    }

    private boolean acceptChosen(Candidate chosen, ParsedLine parsed) {
        if (!suffixApplied(chosen, parsed)) {
            writeCandidate(chosen, parsed, true);
            clearSlashList();
            return true;
        }
        clearSlashList();
        return acceptLine();
    }

    /**
     * 模型参数不走前缀过滤。当前词已经是某个完整模型名时，再按它匹配只会剩下自己。
     */
    private boolean moveModel(ParsedLine parsed, boolean down) {
        List<Candidate> all = allModels();
        if (all.isEmpty()) {
            clearSlashList();
            return history(down);
        }
        boolean sameMenu = menuActive(parsed) && slashChoices.size() == all.size();
        if (!sameMenu) {
            rememberDraft();
        }
        slashChoices = List.copyOf(all);
        if (sameMenu) {
            slashIndex = shift(slashIndex, down, slashChoices.size());
        } else {
            slashIndex = openingModelIndex(slashChoices, parsed.word(), down);
        }
        Candidate chosen = slashChoices.get(slashIndex);
        writeCandidate(chosen, parsed, false);
        showList(chosen);
        return true;
    }

    private List<Candidate> allModels() {
        String probe = "/model ";
        try {
            return matches(parser.parse(probe, probe.length(), Parser.ParseContext.COMPLETE));
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static int openingModelIndex(List<Candidate> choices, String word, boolean down) {
        if (word == null || word.isEmpty()) {
            return down ? 0 : choices.size() - 1;
        }
        int exact = indexOf(choices, word);
        if (exact >= 0) {
            return shift(exact, down, choices.size());
        }
        int prefix = prefixIndex(choices, word);
        if (prefix >= 0) {
            return prefix;
        }
        return down ? 0 : choices.size() - 1;
    }

    private static int shift(int index, boolean down, int size) {
        return down ? (index + 1) % size : (index - 1 + size) % size;
    }

    private static int indexOf(List<Candidate> choices, String word) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).value().equals(word)) {
                return i;
            }
        }
        return -1;
    }

    private static int prefixIndex(List<Candidate> choices, String word) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).value().startsWith(word)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean modelArgument(ParsedLine parsed) {
        return parsed != null
                && !parsed.words().isEmpty()
                && "/model".equals(parsed.words().get(0))
                && parsed.wordIndex() > 0;
    }

    private boolean history(boolean down) {
        return down ? downLineOrHistory() : upLineOrHistory();
    }

    private boolean slashBuffer() {
        String text = buf.toString();
        int cursor = Math.min(buf.cursor(), text.length());
        int start = 0;
        while (start < cursor && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        return start < cursor && text.charAt(start) == '/';
    }

    private ParsedLine currentLine() {
        return parser.parse(buf.toString(), buf.cursor(), Parser.ParseContext.COMPLETE);
    }

    private List<Candidate> matches(ParsedLine parsed) {
        if (parsed == null || completer == null) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        try {
            completer.complete(this, parsed, candidates);
        } catch (RuntimeException exception) {
            return List.of();
        }
        return candidates;
    }

    private static Candidate exact(List<Candidate> matches, String word) {
        for (Candidate candidate : matches) {
            if (candidate.value().equals(word)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean alreadyChosen(List<Candidate> matches, ParsedLine parsed) {
        if (matches.size() != 1) {
            return false;
        }
        Candidate candidate = matches.get(0);
        return candidate.value().equals(parsed.word()) && suffixApplied(candidate, parsed);
    }

    private boolean suffixApplied(Candidate candidate, ParsedLine parsed) {
        String suffix = candidate.suffix();
        if (suffix == null || suffix.isEmpty()) {
            return true;
        }
        String text = buf.toString();
        int end = buf.cursor() - parsed.wordCursor() + parsed.word().length();
        return end >= 0 && end + suffix.length() <= text.length() && text.startsWith(suffix, end);
    }

    private boolean menuActive(ParsedLine parsed) {
        return parsed != null
                && slashIndex >= 0
                && slashIndex < slashChoices.size()
                && slashChoices.get(slashIndex).value().equals(parsed.word());
    }

    AttributedString visibleMenu() {
        return post == null ? null : post.get();
    }

    private void showList(Candidate selected) {
        List<Candidate> choices = slashChoices;
        post = () -> renderChoices(choices, selected);
    }

    private AttributedString renderChoices(List<Candidate> choices, Candidate selected) {
        AttributedStringBuilder displayed = new AttributedStringBuilder();
        for (int i = 0; i < choices.size(); i++) {
            if (i > 0) {
                displayed.append('\n');
            }
            Candidate choice = choices.get(i);
            boolean highlighted = selected != null && choice.value().equals(selected.value());
            if (highlighted) {
                displayed.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold());
            } else {
                displayed.style(AttributedStyle.DEFAULT);
            }
            displayed.append(choice.displ() == null ? choice.value() : choice.displ());
            if (choice.descr() != null && !choice.descr().isEmpty()) {
                displayed.style(highlighted
                        ? AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)
                        : AttributedStyle.DEFAULT.faint());
                displayed.append("  ").append(choice.descr());
            }
            displayed.style(AttributedStyle.DEFAULT);
        }
        return displayed.toAttributedString();
    }

    private void clearSlashList() {
        slashChoices = List.of();
        slashIndex = -1;
        slashDraft = "";
        slashDraftCursor = 0;
        restoreFooter();
    }

    void setFooter(String footer) {
        footerText = footer == null ? "" : footer;
    }

    private void restoreFooter() {
        if (footerText.isEmpty()) {
            post = null;
            return;
        }
        String text = footerText;
        post = () -> AttributedString.fromAnsi(text);
    }

    private void writeCandidate(Candidate candidate, ParsedLine parsed, boolean withSuffix) {
        String text = buf.toString();
        int start = buf.cursor() - parsed.wordCursor();
        if (start < 0 || start > text.length()) {
            return;
        }
        int end = Math.min(start + parsed.word().length(), text.length());
        String suffix = withSuffix && candidate.suffix() != null ? candidate.suffix() : "";
        String inserted = candidate.value() + suffix;
        buf.clear();
        buf.write(text.substring(0, start) + inserted + text.substring(end));
        buf.cursor(start + inserted.length());
    }

    @Override
    public boolean redisplay() {
        if (slashIndex >= 0) {
            ParsedLine parsed;
            try {
                parsed = currentLine();
            } catch (RuntimeException exception) {
                parsed = null;
            }
            if (!menuActive(parsed)) {
                clearSlashList();
            }
        }
        return super.redisplay();
    }

    /**
     * 补全灰字在真正的终端上会于刷新时按当前词重列候选。模型名已经写进输入后，这次重列只剩它自己，
     * 把原本的三行菜单盖掉。菜单开着时停掉这次重列，保留已经画好的全部候选。
     */
    @Override
    public AttributedString getDisplayedBufferWithPrompts(List<AttributedString> secondaryPrompts) {
        if (slashIndex >= 0 && slashIndex < slashChoices.size()) {
            doAutosuggestion = false;
            showList(slashChoices.get(slashIndex));
        }
        return super.getDisplayedBufferWithPrompts(secondaryPrompts);
    }

    /**
     * 把已经完成的行写到滚动区，并立刻重画提示符。读行还没开始时只把完成的行打出来。
     * 提示符可以是多行：上面是正在生成的半行，下面才是输入。
     */
    void present(List<String> lines, String promptText, String rightPromptText, String footer) {
        lock.lock();
        try {
            setFooter(footer);
            if (!reading || display == null) {
                for (String line : lines) {
                    terminal.writer().println(line == null ? "" : line);
                }
                if (!lines.isEmpty()) {
                    terminal.flush();
                }
                return;
            }
            if (!lines.isEmpty()) {
                display.update(Collections.emptyList(), 0);
                for (String line : lines) {
                    terminal.writer().println(line == null ? "" : line);
                }
            }
            this.prompt = AttributedString.fromAnsi(promptText == null ? "" : promptText);
            setRightPrompt(rightPromptText);
            if (slashIndex < 0) {
                restoreFooter();
            }
            redisplay(false);
            terminal.flush();
        } finally {
            lock.unlock();
        }
    }

    int columns() {
        int width = terminal.getWidth();
        return width > 0 ? width : 80;
    }

    AttributedString paint() {
        if (prompt == null) {
            prompt = new AttributedString("jotaro> ");
        }
        doAutosuggestion = true;
        return getDisplayedBufferWithPrompts(new ArrayList<>());
    }

    AttributedString displayed() {
        if (prompt == null) {
            prompt = new AttributedString("jotaro> ");
        }
        return getDisplayedBufferWithPrompts(new ArrayList<>());
    }

    private void bindEditing(String mapName) {
        KeyMap<Binding> map = getKeyMaps().get(mapName);
        if (map == null) {
            return;
        }
        bind(map, "jotaro-up",
                "\u001b[A", "\u001bOA", KeyMap.key(getTerminal(), InfoCmp.Capability.key_up));
        bind(map, "jotaro-down",
                "\u001b[B", "\u001bOB", KeyMap.key(getTerminal(), InfoCmp.Capability.key_down));
        bind(map, "jotaro-accept", "\r", "\n");
        bind(map, "jotaro-cancel", "\u001b");
        map.bind(new Reference("jotaro-interrupt"), KeyMap.ctrl('C'));
    }

    private void bindMenu() {
        KeyMap<Binding> menu = getKeyMaps().get(LineReader.MENU);
        if (menu == null) {
            return;
        }
        bind(menu, LineReader.UP_LINE_OR_HISTORY,
                "\u001b[A", "\u001bOA", KeyMap.key(getTerminal(), InfoCmp.Capability.key_up));
        bind(menu, LineReader.DOWN_LINE_OR_HISTORY,
                "\u001b[B", "\u001bOB", KeyMap.key(getTerminal(), InfoCmp.Capability.key_down));
        bind(menu, "jotaro-menu-accept", "\r", "\n");
    }

    private static void bind(KeyMap<Binding> map, String widget, String... sequences) {
        List<String> present = new ArrayList<>();
        for (String sequence : sequences) {
            if (sequence != null && !sequence.isEmpty()) {
                present.add(sequence);
            }
        }
        if (!present.isEmpty()) {
            map.bind(new Reference(widget), present.toArray(String[]::new));
        }
    }

    @Override
    protected void toColumns(Object items, int width, int max, AttributedStringBuilder sb,
                             Candidate selection, String completed, boolean rowsFirst,
                             boolean doMenuList, int[] out) {
        int cell = max > 0 ? Math.min(width, max) : width;
        super.toColumns(items, cell, max, sb, selection, completed, rowsFirst, doMenuList, out);
    }
}
