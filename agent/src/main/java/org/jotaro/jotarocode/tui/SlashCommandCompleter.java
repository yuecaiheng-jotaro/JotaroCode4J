package org.jotaro.jotarocode.tui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * 斜杠命令的前缀补全。{@code /mo} 只给出 {@code /model}；{@code /model de} 再按模型名继续匹配。
 * 普通文本不参与补全，避免在写任务时被命令打断。
 */
final class SlashCommandCompleter implements Completer {

    private static final List<Command> COMMANDS = List.of(
            new Command("/help", "显示命令", null),
            new Command("/exit", "退出", null),
            new Command("/quit", "退出", null),
            new Command("/abort", "取消当前运行", null),
            new Command("/reset", "清空当前会话", null),
            new Command("/model", "查看或切换模型", " ")
    );

    private final Supplier<? extends Collection<String>> models;

    SlashCommandCompleter(Supplier<? extends Collection<String>> models) {
        this.models = models;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        List<String> words = line.words();
        if (!words.isEmpty() && "/model".equals(words.get(0)) && line.wordIndex() > 0) {
            addPrefixMatches(candidates, models.get(), line.word(), null);
            return;
        }
        String word = line.word();
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

    private static void addPrefixMatches(List<Candidate> candidates, Collection<String> values, String prefix, String suffix) {
        if (values == null) {
            return;
        }
        values.stream()
                .filter(value -> value != null && value.startsWith(prefix))
                .sorted()
                .forEach(value -> candidates.add(new Candidate(value, value, null, null, suffix, null, false)));
    }

    private record Command(String name, String description, String suffix) {
    }
}
