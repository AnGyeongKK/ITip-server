-- 기존 이름·연결을 임의로 변경하지 않는다. 위반 데이터가 있으면 먼저 수동으로 이름을 조정한다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM folders WHERE char_length(name) > 20) THEN
        RAISE EXCEPTION 'Folder names exceed 20 code points. Rename them before applying V8.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM folders
        GROUP BY owner_id, translate(name, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate folder names exist per owner. Rename them before applying V8.';
    END IF;
END $$;

ALTER TABLE folders ADD CONSTRAINT ck_folders_name_length CHECK (char_length(name) BETWEEN 1 AND 20);
CREATE UNIQUE INDEX uq_folders_owner_name ON folders
    (owner_id, translate(name, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'));
