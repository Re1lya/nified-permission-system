"""Assemble an offline Hibernate metadata export with the upstream OIDC and session schemas.
Run ExportSchema.java after mvn -Dmaven.test.skip=true compile dependency:build-classpath.
No database connections or tests are used by this script.
"""
from pathlib import Path
import re,sys
ROOT=Path(__file__).resolve().parents[1]
metadata='\n'.join(line.rstrip() for line in Path(sys.argv[1]).read_text().splitlines())+'\n'
metadata=metadata.replace('engine=InnoDB;', 'engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;')
oidc=(ROOT/'src/main/resources/oidc-schema.sql').read_text()
sessions=(ROOT/'src/main/resources/session-schema-mysql.sql').read_text()
(ROOT/'docs/phase-one/database-mysql-phase-one.sql').write_text('-- New database only. Generated offline from current entities; execute in an empty selected database.\n'+metadata+'\n'+oidc+'\n'+sessions)
# Extract only newly introduced tables, constraints and indexes for the old 25-table installation.
new={'sys_account','application_access','platform_signing_key','permission_review','audit_archive','hr_identity','sync_failure','platform_notification'}
statements=[s.strip()+';' for s in metadata.split(';') if s.strip()]
selected=[]
for statement in statements:
 m=re.search(r'(?:create table|alter table|on)\s+([a-z_]+)',statement,re.I)
 if m and m[1] in new:selected.append(statement)
prefix='''-- Upgrade from 01b94f06 (25-table phase-one schema). Stop writers and back up first.
-- Run once in the selected application database. This script is not an automatic production action.
-- MySQL DDL commits implicitly; restore from backup rather than re-running a partially applied script.
ALTER TABLE sys_user ADD COLUMN built_in_administrator BIT NOT NULL DEFAULT 0;
UPDATE sys_user SET built_in_administrator=1 WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM sys_user WHERE administrator=true) initial_admin);
ALTER TABLE account_policy ADD COLUMN initial_password_issued BIT NOT NULL DEFAULT 1;
ALTER TABLE sys_department ADD COLUMN deleted BIT NOT NULL DEFAULT 0;
ALTER TABLE sys_post ADD COLUMN deleted BIT NOT NULL DEFAULT 0;
ALTER TABLE data_dictionary ADD COLUMN deleted BIT NOT NULL DEFAULT 0;
ALTER TABLE integration_event ADD COLUMN response MEDIUMTEXT NULL;
ALTER TABLE sys_role ADD COLUMN deleted BIT NOT NULL DEFAULT 0;
ALTER TABLE biz_system ADD COLUMN deleted BIT NOT NULL DEFAULT 0;
'''
copy='''
INSERT INTO sys_account(user_id,username,password_hash) SELECT id,username,password_hash FROM sys_user;
INSERT INTO hr_identity(kind,external_id,local_id)
 SELECT 'EMPLOYEE',u.username,u.id FROM sys_user u JOIN employee_profile e ON e.user_id=u.id
 LEFT JOIN account_policy p ON p.user_id=u.id WHERE p.person_type IS NULL OR p.person_type='EMPLOYEE';
ALTER TABLE sys_user DROP INDEX uk_sys_user_username;
ALTER TABLE sys_user DROP COLUMN username, DROP COLUMN password_hash;
-- Preflight: resolve duplicate standalone role names before running this upgrade.
INSERT INTO sys_post(name,role_id,deleted)
 SELECT r.name,r.id,false FROM sys_role r LEFT JOIN sys_post p ON p.role_id=r.id WHERE p.id IS NULL;
-- Existing accounts keep their login names. New HR/imported accounts use pinyin initials.
-- Existing OIDC in-memory authorizations cannot be migrated; users must sign in again.
'''
(ROOT/'docs/migrations/20261022-phase-one-completion.sql').write_text(prefix+'\n\n'.join(selected)+copy+'\n'+oidc+'\n'+sessions)
print('Tables:',len(re.findall(r'create table',metadata+oidc+sessions,re.I)))
