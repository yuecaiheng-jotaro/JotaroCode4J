package org.jotaro.jotarocode.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jotaro.jotarocode.agent.tools.coding.ToolPaths;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内嵌 SQLite（默认 {@code .jotaro/jotaro.db}）的会话上下文持久化存储。
 * <p>
 * 负责将每个会话的元信息（{@code jotaro_sessions}）与不可变事件日志（{@code jotaro_context_events}）长期落盘，
 * 支持 Agent 重启后通过 {@code /resume} 命令按会话列表恢复历史上下文。
 */
@Slf4j
@Component
public class SqliteContextStore implements ContextStore {

    private static final String DEFAULT_DB_PATH = ".jotaro/jotaro.db";

    @Resource
    private JotaroAgentProperties properties;
    @Resource
    private ObjectMapper objectMapper;

    /** 已完成建表初始化的数据库文件路径集合，避免每次读写重复执行 DDL。 */
    private final Set<Path> initializedFiles = ConcurrentHashMap.newKeySet();

    public void bind(JotaroAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(String sessionId, ContextEvent event) {
        if (!StringUtils.hasText(sessionId) || event == null) {
            return;
        }
        try (Connection conn = openConnection()) {
            String json = mapper().writeValueAsString(event);
            long now = event.getTimestamp() > 0 ? event.getTimestamp() : System.currentTimeMillis();

            try (PreparedStatement insertEvent = conn.prepareStatement(
                    "INSERT OR REPLACE INTO jotaro_context_events (session_id, seq, event_type, payload, created_at) VALUES (?, ?, ?, ?, ?)")) {
                insertEvent.setString(1, sessionId);
                insertEvent.setLong(2, event.getSeq());
                insertEvent.setString(3, event.getType() == null ? "USER" : event.getType().name());
                insertEvent.setString(4, json);
                insertEvent.setLong(5, now);
                insertEvent.executeUpdate();
            }

            String titleCandidate = event.getType() == ContextEvent.Type.USER ? shortenTitle(event.getText()) : "";
            try (PreparedStatement upsertSession = conn.prepareStatement("""
                    INSERT INTO jotaro_sessions (session_id, title, created_at, updated_at, event_count)
                    VALUES (?, ?, ?, ?, 1)
                    ON CONFLICT(session_id) DO UPDATE SET
                        title = CASE WHEN jotaro_sessions.title = '' AND excluded.title != '' THEN excluded.title ELSE jotaro_sessions.title END,
                        updated_at = excluded.updated_at,
                        event_count = (SELECT COUNT(*) FROM jotaro_context_events WHERE session_id = excluded.session_id)
                    """)) {
                upsertSession.setString(1, sessionId);
                upsertSession.setString(2, titleCandidate);
                upsertSession.setLong(3, now);
                upsertSession.setLong(4, now);
                upsertSession.executeUpdate();
            }
        } catch (Exception exception) {
            log.warn("写入 SQLite 上下文事件失败: {}", exception.getMessage());
        }
    }

    @Override
    public List<ContextEvent> events(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return List.of();
        }
        Path dbFile = resolveDbFile();
        if (!Files.exists(dbFile)) {
            return List.of();
        }
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT payload FROM jotaro_context_events WHERE session_id = ? ORDER BY seq ASC")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ContextEvent> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapper().readValue(rs.getString("payload"), ContextEvent.class));
                }
                return List.copyOf(list);
            }
        } catch (Exception exception) {
            log.warn("读取 SQLite 上下文事件失败: {}", exception.getMessage());
            return List.of();
        }
    }

    @Override
    public void clear(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        Path dbFile = resolveDbFile();
        if (!Files.exists(dbFile)) {
            return;
        }
        try (Connection conn = openConnection()) {
            try (PreparedStatement delEvents = conn.prepareStatement("DELETE FROM jotaro_context_events WHERE session_id = ?")) {
                delEvents.setString(1, sessionId);
                delEvents.executeUpdate();
            }
            try (PreparedStatement delSession = conn.prepareStatement("DELETE FROM jotaro_sessions WHERE session_id = ?")) {
                delSession.setString(1, sessionId);
                delSession.executeUpdate();
            }
        } catch (Exception exception) {
            log.warn("清空 SQLite 会话事件失败: {}", exception.getMessage());
        }
    }

    @Override
    public List<SessionSummary> listSessions(int limit) {
        Path dbFile = resolveDbFile();
        if (!Files.exists(dbFile)) {
            return List.of();
        }
        int max = limit <= 0 ? 20 : limit;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT session_id, title, updated_at, event_count FROM jotaro_sessions "
                             + "WHERE title != '' AND event_count > 1 ORDER BY updated_at DESC LIMIT ?")) {
            ps.setInt(1, max);
            try (ResultSet rs = ps.executeQuery()) {
                List<SessionSummary> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new SessionSummary(
                            rs.getString("session_id"),
                            rs.getString("title"),
                            rs.getLong("updated_at"),
                            rs.getInt("event_count")
                    ));
                }
                return List.copyOf(results);
            }
        } catch (Exception exception) {
            log.warn("列出 SQLite 历史会话失败: {}", exception.getMessage());
            return List.of();
        }
    }

    @Override
    public String storageKind() {
        return "sqlite";
    }

    private Connection openConnection() throws SQLException, IOException {
        Path dbFile = resolveDbFile();
        Path parent = dbFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout = 5000");
            if (initializedFiles.add(dbFile.toAbsolutePath().normalize())) {
                stmt.execute("PRAGMA journal_mode = WAL");
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS jotaro_sessions (
                            session_id TEXT PRIMARY KEY,
                            title TEXT NOT NULL DEFAULT '',
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            event_count INTEGER NOT NULL DEFAULT 0
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS jotaro_context_events (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            session_id TEXT NOT NULL,
                            seq INTEGER NOT NULL,
                            event_type TEXT NOT NULL,
                            payload TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            UNIQUE(session_id, seq)
                        )
                        """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ctx_events_session_seq ON jotaro_context_events(session_id, seq)");
            }
        }
        return conn;
    }

    private Path resolveDbFile() {
        String configured = properties != null && properties.getSqlite() != null
                && StringUtils.hasText(properties.getSqlite().getPath())
                ? properties.getSqlite().getPath().trim()
                : DEFAULT_DB_PATH;
        return ToolPaths.resolve(configured);
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : new ObjectMapper();
    }

    private static String shortenTitle(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String single = text.replace('\r', ' ').replace('\n', ' ').strip().replaceAll(" +", " ");
        return single.length() > 60 ? single.substring(0, 59) + "…" : single;
    }
}
