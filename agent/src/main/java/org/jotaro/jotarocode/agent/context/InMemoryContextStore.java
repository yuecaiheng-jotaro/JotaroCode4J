package org.jotaro.jotarocode.agent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于进程内存的事件日志存储，用于单元测试、子代理或外部存储不可用时的平滑降级。
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
    public List<SessionSummary> listSessions(int limit) {
        int max = limit <= 0 ? 20 : limit;
        List<SessionSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<ContextEvent>> entry : store.entrySet()) {
            List<ContextEvent> list = events(entry.getKey());
            String title = extractTitle(list);
            if (title.isEmpty()) {
                continue;
            }
            long updatedAt = list.stream().mapToLong(ContextEvent::getTimestamp).max().orElse(0L);
            summaries.add(new SessionSummary(entry.getKey(), title, updatedAt, list.size()));
        }
        summaries.sort(Comparator.comparingLong(SessionSummary::updatedAt).reversed());
        return summaries.size() > max ? List.copyOf(summaries.subList(0, max)) : List.copyOf(summaries);
    }

    @Override
    public String storageKind() {
        return "memory";
    }

    static String extractTitle(List<ContextEvent> events) {
        for (ContextEvent event : events) {
            if (event != null && event.getType() == ContextEvent.Type.USER
                    && event.getText() != null && !event.getText().isBlank()) {
                String single = event.getText().replace('\r', ' ').replace('\n', ' ').strip().replaceAll(" +", " ");
                return single.length() > 60 ? single.substring(0, 59) + "…" : single;
            }
        }
        return "";
    }
}
