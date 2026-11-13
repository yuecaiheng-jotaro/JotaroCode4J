CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS knowledge_base (
    id uuid PRIMARY KEY,
    name text NOT NULL UNIQUE,
    description text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS document (
    id uuid PRIMARY KEY,
    base_id uuid NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    title text NOT NULL,
    source text NOT NULL DEFAULT '',
    content text NOT NULL DEFAULT '',
    chunk_size integer NOT NULL DEFAULT 700,
    chunk_overlap integer NOT NULL DEFAULT 80,
    chunk_mode text NOT NULL DEFAULT 'length',
    chunk_delimiter text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 已有的库补上原文字段和分段参数。CREATE TABLE 不会改已经存在的表。
ALTER TABLE document ADD COLUMN IF NOT EXISTS content text NOT NULL DEFAULT '';
ALTER TABLE document ADD COLUMN IF NOT EXISTS chunk_size integer NOT NULL DEFAULT 700;
ALTER TABLE document ADD COLUMN IF NOT EXISTS chunk_overlap integer NOT NULL DEFAULT 80;
ALTER TABLE document ADD COLUMN IF NOT EXISTS chunk_mode text NOT NULL DEFAULT 'length';
ALTER TABLE document ADD COLUMN IF NOT EXISTS chunk_delimiter text NOT NULL DEFAULT '';

-- 分段保留知识库 ID 便于过滤，删除文档时由外键清理对应分段。
CREATE TABLE IF NOT EXISTS chunk (
    id uuid PRIMARY KEY,
    base_id uuid NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    document_id uuid NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    ordinal integer NOT NULL,
    content text NOT NULL,
    embedding vector(1024),
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS chunk_base_idx ON chunk (base_id);
-- 全文和三元组使用 GIN，向量使用基于余弦距离的 HNSW。
CREATE INDEX IF NOT EXISTS chunk_search_idx ON chunk USING gin (search_vector);
CREATE INDEX IF NOT EXISTS chunk_trgm_idx ON chunk USING gin (content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS chunk_embedding_idx ON chunk USING hnsw (embedding vector_cosine_ops);
