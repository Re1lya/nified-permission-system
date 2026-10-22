-- Upgrade from 01b94f06 (25-table phase-one schema). Stop writers and back up first.
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
create table application_access (
        system_id bigint not null,
        acceptance varchar(2000),
        client_id varchar(100) not null,
        compatibility_plan varchar(2000),
        entry_url varchar(2048) not null,
        protocol varchar(16) not null,
        status varchar(16) not null,
        primary key (system_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

create table audit_archive (
        id bigint not null,
        actor_id bigint,
        archived_at datetime(6) not null,
        created_at datetime(6) not null,
        department_id bigint,
        kind varchar(64) not null,
        result varchar(32) not null,
        target varchar(255) not null,
        target_department_id bigint,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

create table hr_identity (
        id bigint not null auto_increment,
        external_id varchar(128) not null,
        kind varchar(16) not null,
        local_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

create table permission_review (
        id bigint not null auto_increment,
        actor_id bigint not null,
        created_at datetime(6) not null,
        decision varchar(16) not null,
        reason varchar(1000) not null,
        role_id bigint not null,
        user_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

create table platform_notification (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        department_id bigint not null,
        kind varchar(64) not null,
        message varchar(1000) not null,
        user_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

create table platform_signing_key (
        id varchar(64) not null,
        jwk varchar(8000) not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

create table sync_failure (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        digest varchar(64) not null,
        error varchar(1000) not null,
        request_id varchar(64) not null,
        resolution varchar(1000),
        source varchar(16) not null,
        status varchar(16) not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

create table sys_account (
        password_hash varchar(255),
        username varchar(64) not null,
        user_id bigint not null,
        primary key (user_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

alter table application_access
       add constraint UKj2we0sa9ch7ljfmv3aotf55r9 unique (client_id);

alter table hr_identity
       add constraint UKiyvee83xvcg9jufig593mvcvl unique (kind, external_id);

alter table sync_failure
       add constraint UKs890of9ec5a78tcg2q54kmg1i unique (source, request_id);

alter table sys_account
       add constraint uk_sys_account_username unique (username);

alter table sys_account
       add constraint FK6kav8vlbhwmchf475uvbi6tso
       foreign key (user_id)
       references sys_user (id);
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

/*
IMPORTANT:
    If using PostgreSQL:
        - update ALL columns defined with 'blob' to 'text', as PostgreSQL does not support the 'blob' data type.
        - update ALL columns defined with 'timestamp' to 'timestamptz', to ensure that time instants are stored accurately.
    If using MySQL:
        - add 'preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' to JDBC connection URL
          to ensure that time instants are stored accurately. See https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html
*/
CREATE TABLE IF NOT EXISTS oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes blob DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value blob DEFAULT NULL,
    authorization_code_issued_at timestamp DEFAULT NULL,
    authorization_code_expires_at timestamp DEFAULT NULL,
    authorization_code_metadata blob DEFAULT NULL,
    access_token_value blob DEFAULT NULL,
    access_token_issued_at timestamp DEFAULT NULL,
    access_token_expires_at timestamp DEFAULT NULL,
    access_token_metadata blob DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value blob DEFAULT NULL,
    oidc_id_token_issued_at timestamp DEFAULT NULL,
    oidc_id_token_expires_at timestamp DEFAULT NULL,
    oidc_id_token_metadata blob DEFAULT NULL,
    refresh_token_value blob DEFAULT NULL,
    refresh_token_issued_at timestamp DEFAULT NULL,
    refresh_token_expires_at timestamp DEFAULT NULL,
    refresh_token_metadata blob DEFAULT NULL,
    user_code_value blob DEFAULT NULL,
    user_code_issued_at timestamp DEFAULT NULL,
    user_code_expires_at timestamp DEFAULT NULL,
    user_code_metadata blob DEFAULT NULL,
    device_code_value blob DEFAULT NULL,
    device_code_issued_at timestamp DEFAULT NULL,
    device_code_expires_at timestamp DEFAULT NULL,
    device_code_metadata blob DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

/*
IMPORTANT:
    If using PostgreSQL:
        - update ALL columns defined with 'timestamp' to 'timestamptz', to ensure that time instants are stored accurately.
    If using MySQL:
        - add 'preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' to JDBC connection URL
          to ensure that time instants are stored accurately. See https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html
*/
CREATE TABLE IF NOT EXISTS oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamp DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BLOB NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
