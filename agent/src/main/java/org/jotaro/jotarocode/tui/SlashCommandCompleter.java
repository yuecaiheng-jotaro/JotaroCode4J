package org.jotaro.jotarocode.tui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jotaro.jotarocode.agent.context.ContextStore;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * TUI 终端斜杠命令与模型名称补全器。
 * <p>
 * 补全规则：
 * <ul>
 *   <li>仅当第 0 个词以 {@code /} 开头时提供斜杠命令前缀补全（如输入 {@code /mo} 补全为 {@code /model}），普通文本输入不触发补全，避免干扰正常对话输入。</li>
 *   <li>当第一个词已经是 {@code /model} 且光标进入参数位置（{@code wordIndex > 0}）时，按前缀匹配已配置的可用模型列表。</li>
 *   <li>所有 {@link Candidate} 的 {@code complete} 标志均设为 {@code false}，以便交给 {@link SingleColumnLineReader} 控制后缀空格与菜单回车行为，不在选定时被 JLine 自动多追加一个尾随空格。</li>
 * </ul>
 */
final class SlashCommandCompleter implements Completer {

    /** 内置支持的全部斜杠命令定义列表（{@code /model} 带一个空格后缀以引导继续选择模型参数）。 */
    static final List<Command> COMMANDS = List.of(
            new Command("/help", "显示命令", null),
            new Command("/exit", "退出", null),
            new Command("/quit", "退出", null),
            new Command("/abort", "取消当前运行", null),
            new Command("/reset", "清空当前会话", null),
            new Command("/new", "开启新会话（保留当前会话，可用 /resume 恢复）", null),
            new Command("/context", "查看上下文水位、Redis/SQLite 存储与压缩状态", null),
            new Command("/goal", "锚定当前任务目标（防止长对话偏航）", " "),
            new Command("/compact", "子代理压缩上下文（默认 70%）", null),
            new Command("/resume", "从 SQLite 恢复历史会话上下文", " "),
            new Command("/model", "查看或切换模型", " ")
    );

    /** 动态提供当前可用模型名称集合的回调。 */
    private final Supplier<? extends Collection<String>> models;
    /** 动态提供 SQLite 中可恢复历史会话列表的回调。 */
    private final Supplier<List<ContextStore.SessionSummary>> sessions;

    SlashCommandCompleter(Supplier<? extends Collection<String>> models) {
        this(models, List::of);
    }

    SlashCommandCompleter(Supplier<? extends Collection<String>> models,
                          Supplier<List<ContextStore.SessionSummary>> sessions) {
        this.models = models;
        this.sessions = sessions == null ? List::of : sessions;
    }

    /**
     * 根据当前解析行生成斜杠命令、模型名或历史会话参数的补全候选列表。
     */
    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        List<String> words = line.words();
        // 处于 /model 命令后的参数位时，补全可用模型名
        if (!words.isEmpty() && "/model".equals(words.get(0)) && line.wordIndex() > 0) {
            addPrefixMatches(candidates, models.get(), line.word(), null);
            return;
        }
        // 处于 /resume 命令后的参数位时，补全 SQLite 中的历史会话
        if (!words.isEmpty() && "/resume".equals(words.get(0)) && line.wordIndex() > 0) {
            String prefix = line.word() == null ? "" : line.word();
            List<ContextStore.SessionSummary> list = sessions.get();
            if (list != null) {
                for (ContextStore.SessionSummary summary : list) {
                    if (summary.sessionId().startsWith(prefix)) {
                        String shortId = summary.sessionId().length() > 8
                                ? summary.sessionId().substring(0, 8)
                                : summary.sessionId();
                        String desc = summary.title() + " (" + summary.eventCount() + " 条事件)";
                        candidates.add(new Candidate(summary.sessionId(), shortId, null, desc, null, null, false));
                    }
                }
            }
            return;
        }
        String word = line.word();
        // 非首个词或未以 / 开头时，不提供任何补全
        if (line.wordIndex() != 0 || !word.startsWith("/")) {
            return;
        }
        for (Command command : COMMANDS) {
            if (command.name.startsWith(word)) {
                // complete 保持 false，菜单回车只写入命令本身，不额外补一个空格。
                candidates.add(new Candidate(command.name, command.name, null, command.description, command.suffix, null, false));
            }
        }
    }

    /**
     * 从给定字符串集合中筛选匹配指定前缀的条目，排序后加入候选列表。
     */
    private static void addPrefixMatches(List<Candidate> candidates, Collection<String> values, String prefix, String suffix) {
        if (values == null) {
            return;
        }
        values.stream()
                .filter(value -> value != null && value.startsWith(prefix))
                .sorted()
                .forEach(value -> candidates.add(new Candidate(value, value, null, null, suffix, null, false)));
    }

    /**
     * 单条内置斜杠命令元数据。
     *
     * @param name        命令名（含前导 {@code /}）
     * @param description 命令简短说明（显示在候选列表右侧）
     * @param suffix      选定该命令后自动追加的后缀（如 {@code /model} 的空格）
     */
    record Command(String name, String description, String suffix) {
    }
}
