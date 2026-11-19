-- App 7 is a local teaching demo: submitted credentials are intentionally readable.
CREATE TABLE IF NOT EXISTS app7_transport_submissions (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username text NOT NULL CHECK (char_length(username) BETWEEN 1 AND 100),
    password text NOT NULL CHECK (char_length(password) BETWEEN 1 AND 128),
    created_at timestamptz NOT NULL DEFAULT now()
);
