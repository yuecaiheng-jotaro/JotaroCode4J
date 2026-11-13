package org.jotaro.jotarocode.agent.runtime;

import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行中排队的用户消息。默认一次只取出最早的一条，其余留到后续轮次。
 */
public final class PendingMessageQueue {

    private final List<Message> messages = new ArrayList<>();

    public synchronized void enqueue(Message message) {
        messages.add(message);
    }

    public synchronized boolean hasItems() {
        return !messages.isEmpty();
    }

    public synchronized List<Message> drain() {
        if (messages.isEmpty()) {
            return List.of();
        }
        return List.of(messages.remove(0));
    }

    public synchronized void clear() {
        messages.clear();
    }
}
