-- 已有七张表的数据库升级；在备份后由数据库维护者执行一次。
-- 不自动把任何现有账号提升为管理员。随后用启动环境变量创建一个新管理员。
ALTER TABLE sys_user ADD COLUMN administrator BOOLEAN NOT NULL DEFAULT FALSE;
