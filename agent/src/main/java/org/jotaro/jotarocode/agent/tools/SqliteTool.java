package org.jotaro.jotarocode.agent.tools;

import jakarta.annotation.Resource;
import org.jotaro.jotarocode.agent.sqlite.SqliteClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 内置 SQLite 数据库工具。
 * <p>
 * 默认操作当前工作区下的 {@code .jotaro/jotaro.db}（自动创建），
 * 支持建表、插入、更新、删除、查询以及 {@code .tables} / {@code .schema} 查看表结构。
 */
@Component
public class SqliteTool implements ToolContributor {

    @Resource
    private SqliteClient client;

    @Override
    public String displayLabel() {
        return "SQLite";
    }

    @Tool(
            name = "sqlite",
            description = "Execute SQL on the embedded SQLite database (defaults to .jotaro/jotaro.db in the current workspace). "
                    + "Use this tool to create tables, insert/update/delete records, query structured data, "
                    + "or pass '.tables' / '.schema' as sql to inspect existing tables and DDL."
    )
    public String sqlite(
            @ToolParam(description = "SQL statement to execute (e.g. SELECT, CREATE TABLE, INSERT, UPDATE, DELETE) or '.tables' / '.schema'")
            String sql,
            @ToolParam(description = "Optional path to a specific SQLite .db file (relative to workspace or absolute). Omit to use the default .jotaro/jotaro.db", required = false)
            String database
    ) {
        return client.execute(sql, database);
    }
}
