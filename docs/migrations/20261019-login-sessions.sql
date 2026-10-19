-- 已存在六张 RBAC 表的 MySQL 环境使用本增量脚本。
-- 部署新应用版本前执行；本脚本只增加会话表，不修改已有账号和权限数据。
-- 若使用其他数据库名，请将下一行替换为实际应用数据库名。
USE unified_permission;

CREATE TABLE IF NOT EXISTS sys_login_session (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    issued_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_login_session_user (user_id),
    KEY idx_login_session_expires (expires_at),
    CONSTRAINT fk_login_session_user FOREIGN KEY (user_id)
        REFERENCES sys_user (id)
) ENGINE = InnoDB;

-- 旧版 JWT 没有对应的持久化会话，升级后需要重新登录。
-- 多实例升级时需统一切换到新版本，避免旧版绕过持久化会话校验。
-- 可由数据库维护任务定期清理已过期的会话（无需保留明文 JWT）：
-- DELETE FROM sys_login_session WHERE expires_at <= UTC_TIMESTAMP(6);
