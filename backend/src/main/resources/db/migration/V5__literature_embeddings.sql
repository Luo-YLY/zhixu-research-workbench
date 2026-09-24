CREATE TABLE literature_embedding (
  chunk_id VARCHAR(36) NOT NULL REFERENCES literature_chunk(id) ON DELETE CASCADE,
  model_id VARCHAR(240) NOT NULL,
  chunk_sha256 VARCHAR(64) NOT NULL,
  dimension INTEGER NOT NULL,
  vector_base64 TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (chunk_id,model_id)
);
CREATE INDEX literature_embedding_model_idx ON literature_embedding(model_id);
