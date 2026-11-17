package org.jotaro.jotarocode.agent.runtime;

import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行期间排队的用户消息缓冲队列（用于实现 {@code steeringQueue} 和 {@code followUpQueue}）。
 *
 * <p>消费语义设计：
 * {@link #drain()} 每次调用<b>只取出队首最早的一条消息</b>（FIFO），而不是一次性清空全部排队消息。
 * 这样设计是因为每插入一条用户消息都应该触发一次完整的模型推理与回复，余下的消息留在队列中等后续轮次逐条消费。
 * 所有操作均通过 {@code synchronized} 保证多线程并发入队与出队的线程安全。
 */
public final class PendingMessageQueue {

    /** 内部按入队顺序排列的消息列表。 */
    private final List<Message> messages = new ArrayList<>();

    /**
     * 将一条新消息追加到队尾。
     *
     * @param message 待排队的的消息
     */
    public synchronized void enqueue(Message message) {
        messages.add(message);
    }

    /**
     * 判断队列中是否还有待消费的消息。
     *
     * @return {@code true} 表示队列非空
     */
    public synchronized boolean hasItems() {
        return !messages.isEmpty();
    }

    /**
     * 取出并移除队列中最早的一条消息，封装为单元素列表返回；若队列为空则返回空列表。
     *
     * @return 空列表或仅含队首一条消息的列表
     */
    public synchronized List<Message> drain() {
        if (messages.isEmpty()) {
            return List.of();
        }
        return List.of(messages.remove(0));
    }

    /**
     * 清空队列中的所有待处理消息（用于会话 {@code reset()} 时）。
     */
    public synchronized void clear() {
        messages.clear();
    }
}
