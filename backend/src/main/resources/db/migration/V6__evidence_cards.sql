CREATE TABLE evidence_card (
  id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL REFERENCES project(id),
  document_id VARCHAR(36) NOT NULL REFERENCES literature_document(id),
  chunk_id VARCHAR(36) NOT NULL REFERENCES literature_chunk(id),
  document_sha256 VARCHAR(64) NOT NULL,
  chunk_sha256 VARCHAR(64) NOT NULL,
  page_number INTEGER NOT NULL,
  source_quote TEXT NOT NULL,
  research_claim VARCHAR(500) NOT NULL,
  data_requirements VARCHAR(2000) NOT NULL,
  availability_note VARCHAR(1000) NOT NULL,
  reproduction_steps VARCHAR(4000) NOT NULL,
  observation VARCHAR(4000) NOT NULL,
  discrepancy VARCHAR(4000) NOT NULL,
  status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','REVIEWED')),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  reviewed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX evidence_card_project_idx ON evidence_card(project_id,created_at);
CREATE INDEX evidence_card_document_idx ON evidence_card(document_id,page_number);
