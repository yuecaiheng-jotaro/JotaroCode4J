package org.jotaro.rag.index;

/**
 * PostgreSQL {@code pgvector} 扩展向量的文本序列化工具。
 * <p>
 * 将 Java {@code float[]} 数组格式化为 pgvector 可接受的字面量形式（例如 {@code [0.1,0.2]}），
 * 以便在 SQL 中通过 {@code CAST(:embedding AS vector)} 写入或检索。
 */
final class PostgresVectors {

    private PostgresVectors() {
    }

    /**
     * 将浮点数向量数组拼接为 {@code [v1,v2,...]} 格式的字符串。
     *
     * @param embedding 非空浮点数向量数组
     * @return pgvector 字面量字符串
     */
    static String of(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("向量是空的");
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(embedding[index]);
        }
        return builder.append(']').toString();
    }
}
