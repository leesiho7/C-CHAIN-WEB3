CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE trading_knowledge (
  id SERIAL PRIMARY KEY,
  strategy_text TEXT,
  strategy_vector VECTOR(1536)
);
