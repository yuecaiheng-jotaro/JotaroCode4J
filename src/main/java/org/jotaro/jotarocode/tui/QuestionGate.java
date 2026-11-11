package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.runtime.AgentLoopRequest;
import org.jotaro.jotarocode.agent.runtime.BeforeToolCallResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * 在工具线程上拦住复杂调用，直到终端读到回答。一次只问一个，后面的调用排队。
 * {@code a} 之后本次会话不再问。Ctrl+C 会把正在等的问卷按拒绝放开，避免线程一直停着。
 */
final class QuestionGate {

    private final Consumer<List<String>> printer;
    private final Object lock = new Object();
    private boolean allowAll;
    private CompletableFuture<BeforeToolCallResult> pending;

    QuestionGate(Consumer<List<String>> printer) {
        this.printer = printer;
    }

    BeforeToolCallResult beforeCall(AgentLoopRequest.BeforeToolCallContext context) {
        String toolName = context.getToolCall().name();
        if (!ToolQuestionnaire.needsAsk(toolName)) {
            return null;
        }
        CompletableFuture<BeforeToolCallResult> future = new CompletableFuture<>();
        synchronized (lock) {
            while (pending != null && !allowAll) {
                try {
                    lock.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return blocked("已中断");
                }
            }
            if (allowAll) {
                return null;
            }
            pending = future;
        }
        printer.accept(ToolQuestionnaire.prompt(toolName, context.getArguments()));
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.complete(blocked("已中断"));
            return blocked("已中断");
        } catch (ExecutionException exception) {
            return blocked("问卷没有完成");
        } finally {
            synchronized (lock) {
                if (pending == future) {
                    pending = null;
                }
                lock.notifyAll();
            }
        }
    }

    /** @return 这行是问卷回答，不要再发给模型 */
    boolean answer(String line) {
        CompletableFuture<BeforeToolCallResult> future;
        synchronized (lock) {
            future = pending;
        }
        if (future == null) {
            return false;
        }
        ToolQuestionnaire.Parsed parsed = ToolQuestionnaire.parse(line);
        if (parsed.kind() == ToolQuestionnaire.Parsed.Kind.AGAIN) {
            printer.accept(List.of("请回答 y 允许这次，a 全部允许，n 拒绝，或直接输入提示"));
            return true;
        }
        if (parsed.kind() == ToolQuestionnaire.Parsed.Kind.ALLOW_ALL) {
            synchronized (lock) {
                allowAll = true;
            }
            printer.accept(List.of("已全部允许，本次会话不再询问"));
        }
        future.complete(toResult(parsed));
        return true;
    }

    boolean isWaiting() {
        synchronized (lock) {
            return pending != null;
        }
    }

    void cancel() {
        synchronized (lock) {
            if (pending != null) {
                pending.complete(blocked("已中断"));
            }
            lock.notifyAll();
        }
    }

    void reset() {
        synchronized (lock) {
            allowAll = false;
        }
    }

    private static BeforeToolCallResult toResult(ToolQuestionnaire.Parsed parsed) {
        return switch (parsed.kind()) {
            case ALLOW_ONCE, ALLOW_ALL -> null;
            case DENY -> blocked("用户拒绝执行这次工具");
            case HINT -> blocked("用户提示，未执行: " + parsed.hint());
            case AGAIN -> null;
        };
    }

    private static BeforeToolCallResult blocked(String reason) {
        return BeforeToolCallResult.builder().block(true).reason(reason).build();
    }
}
