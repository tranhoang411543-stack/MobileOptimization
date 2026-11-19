-- A NULL value means no modification time was recorded; do not invent history for older notes.
ALTER TABLE app6_notes ADD COLUMN IF NOT EXISTS updated_at timestamptz;

CREATE OR REPLACE FUNCTION app6_notes_record_update_time()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.title, NEW.content, NEW.encrypted)
       IS DISTINCT FROM ROW(OLD.title, OLD.content, OLD.encrypted) THEN
        NEW.updated_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgrelid = 'app6_notes'::regclass
          AND tgname = 'app6_notes_record_update_time'
          AND NOT tgisinternal
    ) THEN
        CREATE TRIGGER app6_notes_record_update_time
        BEFORE UPDATE OF title, content, encrypted ON app6_notes
        FOR EACH ROW EXECUTE FUNCTION app6_notes_record_update_time();
    END IF;
END;
$$;
