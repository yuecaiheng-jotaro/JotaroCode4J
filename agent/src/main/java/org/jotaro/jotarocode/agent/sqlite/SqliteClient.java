package org.jotaro.jotarocode.agent.sqlite;

import jakarta.annotation.Resource;
import org.jotaro.jotarocode.agent.tools.coding.ToolOutput;
import org.jotaro.jotarocode.agent.tools.coding.ToolPaths;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 内嵌 SQLite 数据库客户端。
 * <p>
 * 默认连接当前工作区下的 {@code .jotaro/jotaro.db}（父目录与库文件在首次访问时自动创建），
 * 也允许工具调用时传入自定义 {@code database} 路径操作工作区内的其他 SQLite 文件。
 * 支持 {@code .tables}、{@code .schema} 快捷指令以及标准 DDL / DML / DQL 语句。
 */
@Component
public class SqliteClient {

    private static final String DEFAULT_DB_PATH = ".jotaro/jotaro.db";
    private static final int DEFAULT_MAX_ROWS = 200;

    @Resource
    private JotaroAgentProperties properties;

    public void bind(JotaroAgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 在目标 SQLite 数据库上执行 SQL 或元信息指令。
     *
     * @param sql      要执行的 SQL 语句，或 {@code .tables} / {@code .schema}
     * @param database 可选数据库文件路径（相对当前工作目录或绝对路径）；为空时使用默认 {@code .jotaro/jotaro.db}
     * @return 格式化后的查询结果表、受影响行数或错误描述
     */
    public String execute(String sql, String database) {
        if (!StringUtils.hasText(sql)) {
            return "SQL 语句不能为空。";
        }
        Path dbFile = resolveDatabasePath(database);
        try {
            Path parent = dbFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exception) {
            return "创建 SQLite 目录失败: " + exception.getMessage();
        }

        String trimmed = sql.trim();
        String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");

            if (".tables".equalsIgnoreCase(trimmed) || "tables".equalsIgnoreCase(trimmed)) {
                return listTables(statement);
            }
            if (".schema".equalsIgnoreCase(trimmed) || "schema".equalsIgnoreCase(trimmed)) {
                return dumpSchema(statement);
            }
            return executeSql(statement, trimmed);
        } catch (SQLException exception) {
            return "SQLite 执行错误: " + exception.getMessage();
        }
    }

    /**
     * 解析数据库文件路径。未指定时读取 {@code jotaro.sqlite.path}（默认 {@code .jotaro/jotaro.db}）。
     */
    Path resolveDatabasePath(String database) {
        if (StringUtils.hasText(database)) {
            return ToolPaths.resolve(database.trim());
        }
        String configured = properties != null && properties.getSqlite() != null
                && StringUtils.hasText(properties.getSqlite().getPath())
                ? properties.getSqlite().getPath().trim()
                : DEFAULT_DB_PATH;
        return ToolPaths.resolve(configured);
    }

    private int maxRows() {
        if (properties != null && properties.getSqlite() != null && properties.getSqlite().getMaxRows() > 0) {
            return properties.getSqlite().getMaxRows();
        }
        return DEFAULT_MAX_ROWS;
    }

    private String listTables(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' ORDER BY type, name")) {
            List<String> items = new ArrayList<>();
            while (rs.next()) {
                items.add(rs.getString("name") + " (" + rs.getString("type") + ")");
            }
            if (items.isEmpty()) {
                return "当前数据库暂无用户表或视图。";
            }
            return String.join("\n", items);
        }
    }

    private String dumpSchema(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY type, name")) {
            List<String> ddls = new ArrayList<>();
            while (rs.next()) {
                ddls.add(rs.getString("sql") + ";");
            }
            if (ddls.isEmpty()) {
                return "当前数据库暂无表结构定义。";
            }
            return String.join("\n\n", ddls);
        }
    }

    private String executeSql(Statement statement, String sql) throws SQLException {
        boolean hasResultSet = statement.execute(sql);
        if (!hasResultSet) {
            int updated = statement.getUpdateCount();
            return "执行成功，影响行数: " + Math.max(0, updated);
        }
        int limit = maxRows();
        try (ResultSet rs = statement.getResultSet()) {
            return formatResultSet(rs, limit);
        }
    }

    /**
     * 将查询结果集格式化为 Markdown 表格，超过 {@code maxRows} 或 50KB 时自动截断。
     */
    String formatResultSet(ResultSet rs, int limit) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        List<String> headers = new ArrayList<>(columns);
        for (int i = 1; i <= columns; i++) {
            headers.add(meta.getColumnLabel(i));
        }

        List<List<String>> rows = new ArrayList<>();
        boolean moreRows = false;
        while (rs.next()) {
            if (rows.size() >= limit) {
                moreRows = true;
                break;
            }
            List<String> row = new ArrayList<>(columns);
            for (int i = 1; i <= columns; i++) {
                Object val = rs.getObject(i);
                row.add(val == null ? "NULL" : sanitizeCell(val.toString()));
            }
            rows.add(row);
        }

        if (rows.isEmpty()) {
            return "查询完成，0 行返回（列: " + String.join(", ", headers) + "）。";
        }

        StringBuilder table = new StringBuilder();
        table.append("| ").append(String.join(" | ", headers)).append(" |\n");
        table.append("| ").append(headers.stream().map(h -> "---").reduce((a, b) -> a + " | " + b).orElse("---")).append(" |");
        for (List<String> row : rows) {
            table.append("\n| ").append(String.join(" | ", row)).append(" |");
        }

        ToolOutput.Truncation truncation = ToolOutput.head(table.toString(), Integer.MAX_VALUE, ToolOutput.MAX_BYTES);
        String output = truncation.content();
        if (moreRows) {
            output += "\n\n[已截断：仅显示前 " + limit + " 行，请在 SQL 中添加 LIMIT / WHERE 缩小范围]";
        } else {
            output += "\n\n(" + rows.size() + " 行)";
        }
        return output;
    }

    private static String sanitizeCell(String text) {
        return text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace("|", "\\|");
    }
}
