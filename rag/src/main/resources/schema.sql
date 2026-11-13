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
    created_at timestamptz NOT NULL DEFAULT now()
);

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
CREATE INDEX IF NOT EXISTS chunk_search_idx ON chunk USING gin (search_vector);
CREATE INDEX IF NOT EXISTS chunk_trgm_idx ON chunk USING gin (content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS chunk_embedding_idx ON chunk USING hnsw (embedding vector_cosine_ops);
