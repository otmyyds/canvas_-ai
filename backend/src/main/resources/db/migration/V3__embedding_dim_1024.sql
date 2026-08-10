-- embedding 提供方改为阿里百炼 text-embedding-v3，输出 1024 维
ALTER TABLE document_chunks ALTER COLUMN embedding TYPE vector(1024);
