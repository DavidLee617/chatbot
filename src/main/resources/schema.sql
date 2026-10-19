-- T8 长期记忆。只存提炼出的要点，不存原始对话。
CREATE TABLE IF NOT EXISTS user_memory (
    id          UUID PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    kind        VARCHAR(16)  NOT NULL,          -- PREFERENCE / BACKGROUND / TOPIC
    content     TEXT         NOT NULL,
    embedding   REAL[]       NOT NULL,          -- text-embedding-v4，1024 维；召回时在 Java 里算余弦
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_user_memory_user ON user_memory (user_id);
