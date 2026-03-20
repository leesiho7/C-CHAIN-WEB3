-- pgvector extension is already installed on the VectorDB service
-- CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS trading_knowledge (
  id SERIAL PRIMARY KEY,
  strategy_text TEXT,
  strategy_vector VECTOR(1536)
);
