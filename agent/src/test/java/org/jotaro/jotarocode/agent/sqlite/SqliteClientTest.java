package org.jotaro.jotarocode.agent.sqlite;

import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteClientTest {

    @TempDir
    Path temp;

    @Test
    void createsDatabaseExecutesDdlDmlQueriesAndInspectsSchema() {
        Path dbFile = temp.resolve(".jotaro/jotaro.db");
        JotaroAgentProperties properties = new JotaroAgentProperties();
        properties.getSqlite().setPath(dbFile.toString());

        SqliteClient client = new SqliteClient();
        client.bind(properties);

        assertEquals("当前数据库暂无用户表或视图。", client.execute(".tables", null));

        String createResult = client.execute(
                "CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, done INTEGER DEFAULT 0)",
                null);
        assertTrue(createResult.startsWith("执行成功"));
        assertTrue(Files.exists(dbFile));

        assertEquals("执行成功，影响行数: 2",
                client.execute("INSERT INTO tasks (title, done) VALUES ('接入联网搜索', 1), ('接入内嵌SQLite', 0)", null));

        String tables = client.execute(".tables", null);
        assertTrue(tables.contains("tasks (table)"), tables);

        String schema = client.execute(".schema", null);
        assertTrue(schema.contains("CREATE TABLE tasks"), schema);

        String queryResult = client.execute("SELECT id, title, done FROM tasks ORDER BY id", null);
        assertTrue(queryResult.contains("| id | title | done |"), queryResult);
        assertTrue(queryResult.contains("| 1 | 接入联网搜索 | 1 |"), queryResult);
        assertTrue(queryResult.contains("| 2 | 接入内嵌SQLite | 0 |"), queryResult);
        assertTrue(queryResult.contains("(2 行)"), queryResult);
    }

    @Test
    void truncatesRowsBeyondConfiguredMaxRowsAndHandlesErrorsGracefully() {
        Path dbFile = temp.resolve("custom.db");
        JotaroAgentProperties properties = new JotaroAgentProperties();
        properties.getSqlite().setMaxRows(2);

        SqliteClient client = new SqliteClient();
        client.bind(properties);

        client.execute("CREATE TABLE nums (n INTEGER)", dbFile.toString());
        client.execute("INSERT INTO nums VALUES (1), (2), (3)", dbFile.toString());

        String result = client.execute("SELECT n FROM nums ORDER BY n", dbFile.toString());
        assertTrue(result.contains("| 1 |"), result);
        assertTrue(result.contains("| 2 |"), result);
        assertTrue(result.contains("[已截断：仅显示前 2 行"), result);

        assertEquals("SQL 语句不能为空。", client.execute("   ", dbFile.toString()));
        assertTrue(client.execute("SELECT * FROM non_existent_table", dbFile.toString()).startsWith("SQLite 执行错误:"));
    }
}
