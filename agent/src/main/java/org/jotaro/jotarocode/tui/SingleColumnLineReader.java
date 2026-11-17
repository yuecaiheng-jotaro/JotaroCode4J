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

import org.springframework.ai.content.Media;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 定制的 JLine 行读取器，支持单列纵向候选菜单、实心圆高亮、粘贴块折叠与信号安全拦截。
 * <p>
 * 核心交互机制：
 * <ul>
 *   <li><b>单列候选列表与实心圆高亮</b>：JLine 默认会把短候选词按终端宽度横向平铺在同一行（如 {@code /help /exit /model}）。
 *       本类重写 {@link #toColumns} 并通过 {@link #renderChoices} 将所有候选固定按单列纵向排列，当前选中项前面渲染绿色加粗实心圆 {@code ● }，其余项留两个空格缩进。</li>
 *   <li><b>斜杠命令与模型列表导航</b>：普通文本输入时，上下方向键回溯历史记录；当行首为 {@code /} 时，上下键在匹配的斜杠命令列表或 {@code /model} 的完整模型列表中循环移动，回车选定。
 *       选定 {@code /model} 后会自动补齐空格并直接展开模型列表，且光标默认定位在当前生效的模型上；切换过程中不会因已填入完整模型名而把其余模型过滤掉。</li>
 *   <li><b>取消选择与中断（Esc / Ctrl+C / Ctrl+Z）</b>：读取期间临时关闭终端 {@code ISIG} 信号，使 {@code Ctrl+C} 和 {@code Ctrl+Z} 作为普通按键进入自定义 Widget。
 *       当斜杠候选菜单打开时，按 {@code Esc} 或 {@code Ctrl+C} 会取消本次选择、恢复菜单打开前的输入草稿并收起列表；无菜单时，{@code Ctrl+C} 在空闲态清空输入、在运行态触发 {@code State.INTERRUPT} 中断任务；{@code Ctrl+Z} 先恢复终端属性再安全退出进程。</li>
 *   <li><b>粘贴折叠与原子删除</b>：接管括号粘贴（{@link #beginPaste()}）与 {@code Ctrl+V}（{@link #pasteClipboard()}），通过 {@link PasteChunks} 将超长文本或图片折叠为单行标签，并在退格/删除键触碰标签时整段原子删除。</li>
 * </ul>
 */
final class SingleColumnLineReader extends LineReaderImpl {

    /** 当前打开的斜杠命令或模型候选列表。 */
    private List<Candidate> slashChoices = List.of();
    /** 当前在 {@link #slashChoices} 中高亮选中的下标（{@code -1} 表示未打开斜杠菜单）。 */
    private int slashIndex = -1;
    /** 打开斜杠候选菜单前保存的用户原始输入草稿，供按 {@code Esc} / {@code Ctrl+C} 取消时恢复。 */
    private String slashDraft = "";
    /** 打开斜杠候选菜单前保存的光标位置。 */
    private int slashDraftCursor;
    /** 输入框底栏状态提示文本（当未展开候选菜单时挂载到 {@code post} 区域显示）。 */
    private String footerText = "";
    /** 当前会话输入框内的粘贴文本与图片折叠块管理器。 */
    private final PasteChunks pastes = new PasteChunks();

    /** 判断 Agent 当前是否处于运行状态的回调。 */
    private final BooleanSupplier runActive;
    /** 获取当前会话生效模型名称的回调，用于在打开 {@code /model} 列表时默认高亮当前模型。 */
    private final Supplier<String> currentModel;

    SingleColumnLineReader(Terminal terminal) {
        this(terminal, () -> false, () -> null);
    }

    SingleColumnLineReader(Terminal terminal, BooleanSupplier runActive) {
        this(terminal, runActive, () -> null);
    }

    SingleColumnLineReader(Terminal terminal, BooleanSupplier runActive, Supplier<String> currentModel) {
        super(terminal, "jotaro", new HashMap<>());
        this.runActive = runActive;
        this.currentModel = currentModel == null ? () -> null : currentModel;
    }

    /**
     * 注册自定义交互 Widget 并绑定快捷键：
     * <ul>
     *   <li>普通行：上键回溯已发送的历史行，下键向后移动。</li>
     *   <li>斜杠行：上下键在当前候选列表里移动并实时高亮，回车选定；{@code /model} 选定后自动补上空格并弹出模型列表，且模型名写入缓冲区后不再按该全名收窄列表，上下键始终在全部可用模型之间循环。</li>
     *   <li>已填完整且无后续参数的斜杠命令，上下键仍回溯历史。</li>
     *   <li>候选列表打开时，{@code Esc} 或 {@code Ctrl+C} 取消本次选择、恢复列表打开前的草稿并收起列表。</li>
     *   <li>无候选列表时，{@code Ctrl+C} 在运行中触发中断交给外层中止任务，空闲时原地清空输入；{@code Ctrl+Z} 恢复终端并结束进程；{@code Ctrl+V} 读取剪贴板图片或文本。</li>
     *   <li>配置 {@code HISTORY_IGNORE = "/*"}，使所有斜杠命令不写入历史记录。</li>
     * </ul>
     */
    void installInteractionKeys() {
        getWidgets().put("jotaro-interrupt", this::interruptLine);
        getWidgets().put("jotaro-quit", this::forceQuit);
        getWidgets().put("jotaro-paste", this::pasteClipboard);
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
            // Esc 同时也是方向键转义序列的前缀，缩短歧义超时（50ms）避免单按 Esc 取消时显得迟钝
            map.setAmbiguousTimeout(50);
            bind(map, "jotaro-cancel", "\u001b");
            map.bind(new Reference("jotaro-interrupt"), KeyMap.ctrl('C'));
            map.bind(new Reference("jotaro-quit"), KeyMap.ctrl('Z'));
        }
        setVariable(LineReader.HISTORY_IGNORE, "/*");
    }

    /**
     * 读取一行用户输入。
     * <p>
     * 原始模式下若终端仍开着 {@code ISIG}，按下 {@code Ctrl+C} 会直接由操作系统发送中断信号导致 {@code readLine} 抛异常退出，
     * 无法先优雅收起候选列表或清空输入。因此在读取前先关闭 {@code ISIG}，让 {@code Ctrl+C} 作为普通按键进入 {@link #interruptLine()}，读取结束后再恢复。
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

    /** 关闭终端 {@code ISIG} 本地信号标志。 */
    private boolean disableInterruptSignal() {
        return setInterruptSignal(false);
    }

    /** 恢复开启终端 {@code ISIG} 本地信号标志。 */
    private void enableInterruptSignal() {
        setInterruptSignal(true);
    }

    /**
     * 设置终端的 {@link Attributes.LocalFlag#ISIG} 属性。
     *
     * @param enabled 是否开启信号中断
     * @return 若实际修改了终端属性则返回 {@code true}
     */
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
     * 处理 {@code Ctrl+Z} 强制退出：因读行期间关闭了 {@code ISIG}，终端不再挂起进程，这里先恢复终端规范模式属性再退出 JVM。
     */
    private boolean forceQuit() {
        restoreTerminal();
        System.exit(0);
        return true;
    }

    /**
     * 将终端恢复为规范回显模式（开启 {@code ISIG}、{@code ICANON}、{@code ECHO} 等），避免进程退出后用户 Shell 停留在原始模式。
     */
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

    /**
     * 处理终端括号粘贴（Bracketed Paste，如 macOS 上的 {@code Cmd+V} 或拖入文件）：
     * <ul>
     *   <li>若粘贴内容为空白，尝试检查系统剪贴板是否有截图图片。</li>
     *   <li>若粘贴的是单行本地图片文件路径，折叠为 {@code [Image #N]} 标签。</li>
     *   <li>普通文本交由 {@link PasteChunks#text(String)} 判断是否超过 5 行或 800 字，超长时折叠为 {@code [Pasted text #N +M lines]} 标签。</li>
     * </ul>
     */
    @Override
    public boolean beginPaste() {
        String raw = doReadStringUntil(BRACKETED_PASTE_END);
        regionActive = RegionType.PASTE;
        regionMark = buf.cursor();
        String text = raw.replace('\r', '\n');
        if (text.isBlank()) {
            writeClipboardImage();
            return true;
        }
        String trimmed = text.trim();
        if (!trimmed.contains("\n") && PasteChunks.isImageFile(trimmed)) {
            buf.write(pastes.image(Path.of(trimmed)));
            return true;
        }
        buf.write(pastes.text(text));
        return true;
    }

    /**
     * 处理 {@code Ctrl+V} 显式粘贴：优先读取系统剪贴板中的图片，若无图片则读取剪贴板文本并按需折叠。
     */
    private boolean pasteClipboard() {
        if (writeClipboardImage()) {
            return true;
        }
        String text = ClipboardImages.text();
        if (text.isEmpty()) {
            return true;
        }
        buf.write(pastes.text(text));
        return true;
    }

    /**
     * 若系统剪贴板中存在主图片，则导出为临时文件并向当前输入缓冲区写入 {@code [Image #N]} 标签。
     */
    private boolean writeClipboardImage() {
        Optional<Path> image = ClipboardImages.image();
        if (image.isEmpty()) {
            return false;
        }
        buf.write(pastes.image(image.get()));
        return true;
    }

    /**
     * 判断给定输入行是否包含粘贴折叠标签。
     */
    boolean containsPaste(String line) {
        return pastes.contains(line);
    }

    /**
     * 将输入行中的粘贴折叠标签还原为完整正文与图片路径列表。
     */
    PasteChunks.Expanded expandPaste(String line) {
        return pastes.expand(line);
    }

    /**
     * 将展开后的本地图片路径列表封装为 Spring AI 的 {@link Media} 附件列表。
     */
    List<Media> mediaOf(PasteChunks.Expanded expanded) {
        List<Media> media = new ArrayList<>();
        for (Path path : expanded.images()) {
            var mime = PasteChunks.mime(path);
            if (mime != null) {
                media.add(new Media(mime, new FileSystemResource(path)));
            }
        }
        return media;
    }

    /**
     * 重写退格键（Backspace）：若光标前一个字符属于某个粘贴折叠标签，则一次性删掉整段标签。
     */
    @Override
    protected boolean backwardDeleteChar() {
        if (count == 1 && deletePaste(buf.cursor() - 1)) {
            return true;
        }
        return super.backwardDeleteChar();
    }

    /**
     * 重写删除键（Delete）：若光标当前字符属于某个粘贴折叠标签，则一次性删掉整段标签。
     */
    @Override
    protected boolean deleteChar() {
        if (count == 1 && deletePaste(buf.cursor())) {
            return true;
        }
        return super.deleteChar();
    }

    /**
     * 检查指定字符下标是否落在粘贴折叠标签内，若是则将整段标签从缓冲区删除。
     */
    private boolean deletePaste(int index) {
        PasteChunks.Span span = pastes.covering(buf.toString(), index);
        if (span == null) {
            return false;
        }
        buf.cursor(span.start());
        buf.delete(span.label().length());
        return true;
    }

    /**
     * 处理 {@code Ctrl+C} 按键：
     * 1. 若斜杠候选菜单正处于打开状态，优先取消选择并收起菜单；
     * 2. 若 Agent 正在后台运行，将读行状态设为 {@code State.INTERRUPT} 以抛出 {@link UserInterruptException} 交由外层中止任务；
     * 3. 空闲状态下直接清空当前输入行并关闭自动补全建议。
     */
    private boolean interruptLine() {
        if (slashIndex >= 0) {
            cancelSlashSelection();
            return true;
        }
        if (runActive.getAsBoolean()) {
            state = State.INTERRUPT;
            return true;
        }
        buf.clear();
        clearSlashList();
        doAutosuggestion = false;
        return true;
    }

    /**
     * 取消当前斜杠命令或模型的候选列表选择：
     * 将输入缓冲区恢复为打开菜单前的草稿，清空菜单状态，关闭本轮自动重列，并擦除终端下方残留的候选行。
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

    /**
     * 发送 ANSI {@code ESC [ J} 序列擦除从光标位置到屏幕底部的残留菜单内容。
     */
    private void eraseTail() {
        try {
            terminal.writer().write("\u001b[J");
            terminal.flush();
        } catch (RuntimeException exception) {
            // 刷新失败时仍靠随后的 redisplay 覆盖旧列表
        }
    }

    /** 保存打开斜杠候选列表前的输入行文本与光标位置。 */
    private void rememberDraft() {
        slashDraft = buf.toString();
        slashDraftCursor = buf.cursor();
    }

    /** 将输入缓冲区恢复为打开斜杠候选列表前保存的草稿与光标位置。 */
    private void restoreDraft() {
        buf.clear();
        buf.write(slashDraft);
        buf.cursor(Math.min(Math.max(slashDraftCursor, 0), buf.length()));
    }

    /** 判断当前读行是否已被标记为中断状态。 */
    boolean interrupted() {
        return state == State.INTERRUPT;
    }

    /**
     * 处理上下方向键：
     * <ul>
     *   <li>非斜杠开头时，回退到标准历史记录上下翻页。</li>
     *   <li>处于 {@code /model <arg>} 参数位置时，交给 {@link #moveModel(ParsedLine, boolean)} 在全部可用模型间循环切换。</li>
     *   <li>处于斜杠命令位置时，在匹配的命令候选列表中上下循环移动，将高亮项写入输入行并刷新底部单列菜单。</li>
     * </ul>
     */
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

    /**
     * 处理回车键（Enter）：
     * <ul>
     *   <li>非斜杠命令时直接提交当前行。</li>
     *   <li>若斜杠候选菜单已打开，确认选定当前高亮项（若选定的是 {@code /model} 则自动补空格并展开模型列表，不直接提交）。</li>
     *   <li>若只输入了前缀且唯一匹配（如 {@code /mo} -> {@code /model}），则补全该命令；对 {@code /model} 进一步打开默认定位在当前模型的候选菜单。</li>
     *   <li>若在 {@code /model } 后未选具体模型直接回车，则自动填入当前生效的模型并提交。</li>
     * </ul>
     */
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
            if ("/model".equals(chosen.value())) {
                openDefaultModel();
                return true;
            }
            clearSlashList();
            if (chosen.suffix() != null && !chosen.suffix().isEmpty()) {
                return true;
            }
            return acceptLine();
        }
        if (modelArgument(parsed)) {
            List<Candidate> all = allModels();
            if (!all.isEmpty()) {
                Candidate chosen = all.get(defaultModelIndex(all));
                writeCandidate(chosen, parsed, true);
                clearSlashList();
                return acceptLine();
            }
        }
        rememberDraft();
        slashChoices = List.copyOf(matches);
        slashIndex = 0;
        Candidate chosen = slashChoices.get(0);
        writeCandidate(chosen, parsed, false);
        showList(chosen);
        return true;
    }

    /**
     * 确认选定某个斜杠候选：若该命令带有后缀（如 {@code /model} 的空格后缀）尚未补齐，则先补齐后缀并展开下一级列表；否则收起列表并提交整行。
     */
    private boolean acceptChosen(Candidate chosen, ParsedLine parsed) {
        if (!suffixApplied(chosen, parsed)) {
            writeCandidate(chosen, parsed, true);
            if ("/model".equals(chosen.value())) {
                openDefaultModel();
                return true;
            }
            clearSlashList();
            return true;
        }
        clearSlashList();
        return acceptLine();
    }

    /**
     * 在选定 {@code /model } 后立即展开完整模型候选列表，并将实心圆高亮定位到当前正在使用的模型上。
     */
    private void openDefaultModel() {
        List<Candidate> all = allModels();
        if (all.isEmpty()) {
            clearSlashList();
            return;
        }
        rememberDraft();
        slashChoices = List.copyOf(all);
        slashIndex = defaultModelIndex(slashChoices);
        Candidate chosen = slashChoices.get(slashIndex);
        writeCandidate(chosen, currentLine(), false);
        showList(chosen);
    }

    /**
     * 在 {@code /model } 后的全部可用模型列表之间上下移动。
     * <p>
     * 模型参数不走前缀过滤收窄，因为一旦将某个完整模型名写进输入行，再按前缀匹配就会只剩下它自己，导致无法继续用上下键切换到其他模型。
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

    /**
     * 通过向补全器探测 {@code "/model "} 获取全部可用模型的候选列表。
     */
    private List<Candidate> allModels() {
        String probe = "/model ";
        try {
            return matches(parser.parse(probe, probe.length(), Parser.ParseContext.COMPLETE));
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * 计算初次打开模型候选菜单时的起始高亮下标：
     * 若当前词为空，优先定位到当前会话正在使用的模型；若已有完整模型名，则从该模型向目标方向移动一格；若为部分前缀，则定位到首个前缀匹配项。
     */
    private int openingModelIndex(List<Candidate> choices, String word, boolean down) {
        if (word == null || word.isEmpty()) {
            String active = currentModel.get();
            if (active != null && !active.isBlank()) {
                int currentIdx = indexOf(choices, active.trim());
                if (currentIdx >= 0) {
                    return doAutosuggestion ? shift(currentIdx, down, choices.size()) : currentIdx;
                }
            }
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

    /**
     * 查找当前会话生效模型在候选列表中的下标（未找到时回退到第 0 项）。
     */
    private int defaultModelIndex(List<Candidate> choices) {
        if (choices == null || choices.isEmpty()) {
            return 0;
        }
        String active = currentModel.get();
        if (active != null && !active.isBlank()) {
            int index = indexOf(choices, active.trim());
            if (index >= 0) {
                return index;
            }
        }
        return 0;
    }

    /** 在环形列表中按上下方向移动一格。 */
    private static int shift(int index, boolean down, int size) {
        return down ? (index + 1) % size : (index - 1 + size) % size;
    }

    /** 精确查找候选值等于 {@code word} 的下标。 */
    private static int indexOf(List<Candidate> choices, String word) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).value().equals(word)) {
                return i;
            }
        }
        return -1;
    }

    /** 查找第一个以 {@code word} 为前缀的候选下标。 */
    private static int prefixIndex(List<Candidate> choices, String word) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).value().startsWith(word)) {
                return i;
            }
        }
        return -1;
    }

    /** 判断当前解析行是否正处于 {@code /model} 命令的参数位置（即第二个词及之后）。 */
    private static boolean modelArgument(ParsedLine parsed) {
        return parsed != null
                && !parsed.words().isEmpty()
                && "/model".equals(parsed.words().get(0))
                && parsed.wordIndex() > 0;
    }

    /** 调用 JLine 原生的上下行或历史记录导航。 */
    private boolean history(boolean down) {
        return down ? downLineOrHistory() : upLineOrHistory();
    }

    /** 判断当前输入缓冲区首部非空白字符是否以 {@code /} 开头。 */
    private boolean slashBuffer() {
        String text = buf.toString();
        int cursor = Math.min(buf.cursor(), text.length());
        int start = 0;
        while (start < cursor && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        return start < cursor && text.charAt(start) == '/';
    }

    /** 将当前输入缓冲区与光标位置解析为 JLine {@link ParsedLine}。 */
    private ParsedLine currentLine() {
        return parser.parse(buf.toString(), buf.cursor(), Parser.ParseContext.COMPLETE);
    }

    /** 调用已注册的 {@link SlashCommandCompleter} 获取当前行的匹配候选列表。 */
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

    /** 在候选列表中查找与指定词完全一致的候选项。 */
    private static Candidate exact(List<Candidate> matches, String word) {
        for (Candidate candidate : matches) {
            if (candidate.value().equals(word)) {
                return candidate;
            }
        }
        return null;
    }

    /** 判断当前词是否已经是唯一匹配项且其后缀（如空格）也已写入缓冲区。 */
    private boolean alreadyChosen(List<Candidate> matches, ParsedLine parsed) {
        if (matches.size() != 1) {
            return false;
        }
        Candidate candidate = matches.get(0);
        return candidate.value().equals(parsed.word()) && suffixApplied(candidate, parsed);
    }

    /** 检查候选项定义的后缀（如 {@code /model} 后的空格）是否已紧跟在当前词后面。 */
    private boolean suffixApplied(Candidate candidate, ParsedLine parsed) {
        String suffix = candidate.suffix();
        if (suffix == null || suffix.isEmpty()) {
            return true;
        }
        String text = buf.toString();
        int end = buf.cursor() - parsed.wordCursor() + parsed.word().length();
        return end >= 0 && end + suffix.length() <= text.length() && text.startsWith(suffix, end);
    }

    /** 判断当前斜杠候选菜单是否处于激活状态且当前光标处的词仍与选中项一致。 */
    private boolean menuActive(ParsedLine parsed) {
        return parsed != null
                && slashIndex >= 0
                && slashIndex < slashChoices.size()
                && slashChoices.get(slashIndex).value().equals(parsed.word());
    }

    /** 获取当前挂载在输入框下方（{@code post} 区域）的菜单或底栏文本，供测试断言检查。 */
    AttributedString visibleMenu() {
        return post == null ? null : post.get();
    }

    /** 将斜杠候选列表挂载到 JLine 的 {@code post} 区域进行展示，并指定当前高亮项。 */
    private void showList(Candidate selected) {
        List<Candidate> choices = slashChoices;
        post = () -> renderChoices(choices, selected);
    }

    /**
     * 将候选列表渲染为单列多行文本：
     * 当前选中项（{@code selected}）左侧带绿色加粗实心圆 {@code ● }，描述文字同样着绿色；
     * 未选中项左侧留两个空格，描述文字着暗灰色（faint）。
     */
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
                displayed.append("● ");
            } else {
                displayed.style(AttributedStyle.DEFAULT);
                displayed.append("  ");
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

    /** 清空斜杠候选菜单状态，并将输入框下方恢复为默认底栏（footer）。 */
    private void clearSlashList() {
        slashChoices = List.of();
        slashIndex = -1;
        slashDraft = "";
        slashDraftCursor = 0;
        restoreFooter();
    }

    /** 更新输入框下边框底栏文本。 */
    void setFooter(String footer) {
        footerText = footer == null ? "" : footer;
    }

    /** 当未展示斜杠候选菜单时，将底栏文本挂载到 {@code post} 区域。 */
    private void restoreFooter() {
        if (footerText.isEmpty()) {
            post = null;
            return;
        }
        String text = footerText;
        post = () -> AttributedString.fromAnsi(text);
    }

    /**
     * 将选中的候选词替换写入当前输入缓冲区的对应词位置，并将光标移至该词末尾。
     *
     * @param candidate  要写入的候选项
     * @param parsed     当前行解析结果
     * @param withSuffix 是否同时拼接候选项的后缀（如 {@code /model} 后的空格）
     */
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

    /**
     * 重写重绘逻辑：若用户继续打字使当前词偏离了菜单选中项，则自动收起旧候选菜单并恢复底栏。
     */
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
     * 防止 JLine 自动建议（{@code SuggestionType.COMPLETER}）在菜单打开时按当前完整模型名重算候选列表：
     * 当模型名已被写入输入行后，JLine 默认重列只会剩下该模型一项，从而覆盖掉完整的三行模型菜单。
     * 因此在斜杠菜单激活期间关闭 {@code doAutosuggestion} 并固定展示已保存的完整候选列表。
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
     * 线程安全地将已完成的行输出到终端滚动区，并立即重绘多行输入框（包含上方的实时半行/状态、顶框线、输入行及底框线）。
     * 若此时尚未进入 {@code readLine} 状态，则直接向终端打印完成行。
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

    /** 获取终端当前列宽（获取不到或无效时回退为 80 列）。 */
    int columns() {
        int width = terminal.getWidth();
        return width > 0 ? width : 80;
    }

    /** 触发带自动补全建议的缓冲区渲染（供单元测试验证候选与灰字提示）。 */
    AttributedString paint() {
        if (prompt == null) {
            prompt = new AttributedString("jotaro> ");
        }
        doAutosuggestion = true;
        return getDisplayedBufferWithPrompts(new ArrayList<>());
    }

    /** 获取当前显示的提示符与输入缓冲区内容（供单元测试验证）。 */
    AttributedString displayed() {
        if (prompt == null) {
            prompt = new AttributedString("jotaro> ");
        }
        return getDisplayedBufferWithPrompts(new ArrayList<>());
    }

    /** 在指定编辑键映射表（MAIN / EMACS）上绑定上下方向键、回车、Esc、Ctrl+V 与 Ctrl+C。 */
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
        bind(map, "jotaro-paste", KeyMap.ctrl('V'));
        map.bind(new Reference("jotaro-interrupt"), KeyMap.ctrl('C'));
    }

    /** 在 JLine 的 MENU 键映射表上绑定上下移动与回车确认。 */
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

    /** 过滤掉空序列后将按键序列绑定到指定 Widget。 */
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

    /**
     * 重写 JLine 候选列表分列渲染逻辑：
     * 将所有 {@link Candidate} 强制使用 {@link #renderChoices(List, Candidate)} 排成单列纵向菜单（1 列 × N 行），
     * 并确保默认高亮项在 {@code /model} 下指向当前生效的模型。
     */
    @Override
    protected void toColumns(Object items, int width, int max, AttributedStringBuilder sb,
                             Candidate selection, String completed, boolean rowsFirst,
                             boolean doMenuList, int[] out) {
        if (items instanceof java.util.Collection<?> collection) {
            List<Candidate> candidates = new ArrayList<>();
            for (Object item : collection) {
                if (item instanceof Candidate candidate) {
                    candidates.add(candidate);
                }
            }
            if (!candidates.isEmpty()) {
                Candidate effective = selection != null ? selection : defaultCandidate(candidates);
                sb.append(renderChoices(candidates, effective));
                if (out != null && out.length >= 2) {
                    out[0] = 1;
                    out[1] = candidates.size();
                }
                return;
            }
        }
        int cell = max > 0 ? Math.min(width, max) : width;
        super.toColumns(items, cell, max, sb, selection, completed, rowsFirst, doMenuList, out);
    }

    /**
     * 计算自动补全菜单尚未显式移动焦点时的默认高亮候选项（在 {@code /model} 参数位置默认指向当前模型，其余情况指向首项）。
     */
    private Candidate defaultCandidate(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        try {
            if (modelArgument(currentLine())) {
                return candidates.get(defaultModelIndex(candidates));
            }
        } catch (RuntimeException ignored) {
            // 解析异常时回退到首项
        }
        return candidates.get(0);
    }
}
