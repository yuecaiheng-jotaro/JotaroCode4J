package org.jotaro.rag.index;

/** pgvector 接受的文本形式，例如 {@code [0.1,0.2]}。 */
final class PostgresVectors {

    private PostgresVectors() {
    }

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
