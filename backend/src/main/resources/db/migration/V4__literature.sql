CREATE TABLE literature_document (
  id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL REFERENCES project(id),
  title VARCHAR(240) NOT NULL,
  file_name VARCHAR(240) NOT NULL,
  media_type VARCHAR(80) NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  size_bytes BIGINT NOT NULL,
  page_count INTEGER NOT NULL,
  chunk_count INTEGER NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT literature_document_hash_uq UNIQUE(project_id,sha256)
);
CREATE INDEX literature_document_project_idx ON literature_document(project_id,created_at);

CREATE TABLE literature_chunk (
  id VARCHAR(36) PRIMARY KEY,
  document_id VARCHAR(36) NOT NULL REFERENCES literature_document(id),
  page_number INTEGER NOT NULL,
  chunk_number INTEGER NOT NULL,
  content TEXT NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  CONSTRAINT literature_chunk_position_uq UNIQUE(document_id,page_number,chunk_number)
);
CREATE INDEX literature_chunk_document_idx ON literature_chunk(document_id,page_number,chunk_number);
