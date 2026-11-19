package org.jotaro.jotarocode.agent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于进程内存的事件日志存储，用于单元测试、子代理或 Redis 不可用时的平滑降级。
 */
public final class InMemoryContextStore implements ContextStore {

    private final ConcurrentMap<String, List<ContextEvent>> store = new ConcurrentHashMap<>();

    @Override
    public void append(String sessionId, ContextEvent event) {
        if (sessionId == null || event == null) {
            return;
        }
        store.computeIfAbsent(sessionId, ignored -> Collections.synchronizedList(new ArrayList<>()))
                .add(event);
    }

    @Override
    public List<ContextEvent> events(String sessionId) {
        List<ContextEvent> list = store.get(sessionId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    @Override
    public void clear(String sessionId) {
        if (sessionId != null) {
            store.remove(sessionId);
        }
    }

    @Override
    public String storageKind() {
        return "memory";
    }
}
