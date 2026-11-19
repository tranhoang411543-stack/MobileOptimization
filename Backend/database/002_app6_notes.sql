-- Additive migration: works on new and existing demo databases without changing other tables.
CREATE TABLE IF NOT EXISTS app6_notes (
    id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id uuid NOT NULL UNIQUE,
    title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 120),
    content text NOT NULL,
    encrypted boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
