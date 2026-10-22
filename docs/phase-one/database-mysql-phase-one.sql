-- New database only. Generated offline from current entities; execute in an empty selected database.

    create table account_policy (
        user_id bigint not null,
        deleted bit not null,
        expires_at datetime(6),
        initial_password_issued bit not null,
        must_change_password bit not null,
        person_type varchar(24) not null,
        version bigint not null,
        primary key (user_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table admin_department (
        id bigint not null auto_increment,
        department_id bigint not null,
        user_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

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

    create table approval_record (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        decision varchar(16) not null,
        request_id varchar(64) not null,
        role_id bigint not null,
        user_id bigint not null,
        primary key (id)
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

    create table audit_event (
        id bigint not null auto_increment,
        actor_id bigint,
        created_at datetime(6) not null,
        department_id bigint,
        kind varchar(64) not null,
        result varchar(32) not null,
        target varchar(255) not null,
        target_department_id bigint,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table biz_system (
        id bigint not null auto_increment,
        base_url varchar(255),
        code varchar(64) not null,
        deleted bit not null,
        enabled bit not null,
        name varchar(128) not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table company_import_batch (
        id bigint not null auto_increment,
        actor_id bigint not null,
        created_at datetime(6) not null,
        departments integer not null,
        employees integer not null,
        fingerprint varchar(64) not null,
        functions integer not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table data_dictionary (
        id bigint not null auto_increment,
        category varchar(64) not null,
        code varchar(64) not null,
        deleted bit not null,
        name varchar(128) not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table department_permission (
        id bigint not null auto_increment,
        department_id bigint not null,
        permission_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table employee_profile (
        id bigint not null auto_increment,
        company varchar(128) not null,
        phone varchar(32) not null,
        department_id bigint not null,
        post_id bigint not null,
        user_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table hr_identity (
        id bigint not null auto_increment,
        external_id varchar(128) not null,
        kind varchar(16) not null,
        local_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table integration_event (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        digest varchar(64) not null,
        request_id varchar(64) not null,
        response mediumtext,
        result varchar(32) not null,
        source varchar(16) not null,
        user_id bigint,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table login_attempt (
        username varchar(64) not null,
        failures integer not null,
        locked_until datetime(6),
        primary key (username)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table managed_role (
        role_id bigint not null,
        department_id bigint not null,
        primary key (role_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table permission_detail (
        id bigint not null auto_increment,
        action varchar(32) not null,
        function_id bigint not null,
        permission_id bigint not null,
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

    create table permission_template (
        id bigint not null auto_increment,
        name varchar(64) not null,
        permission_id bigint not null,
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

    create table session_activity (
        session_id varchar(36) not null,
        last_seen datetime(6) not null,
        primary key (session_id)
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

    create table sys_department (
        id bigint not null auto_increment,
        deleted bit not null,
        manager_name varchar(128),
        name varchar(128) not null,
        manager_id bigint,
        parent_id bigint,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table sys_function (
        id bigint not null auto_increment,
        description varchar(2000) not null,
        name varchar(128) not null,
        module_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table sys_login_session (
        id varchar(36) not null,
        expires_at datetime(6) not null,
        issued_at datetime(6) not null,
        user_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table sys_module (
        id bigint not null auto_increment,
        name varchar(128) not null,
        system_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table sys_permission (
        id bigint not null auto_increment,
        active bit not null,
        code varchar(128) not null,
        menu_path varchar(255) not null,
        name varchar(128) not null,
        type varchar(16) not null,
        system_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table sys_post (
        id bigint not null auto_increment,
        deleted bit not null,
        name varchar(128) not null,
        role_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table sys_role (
        id bigint not null auto_increment,
        code varchar(64) not null,
        deleted bit not null,
        description varchar(255),
        enabled bit not null,
        name varchar(128) not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table sys_role_permission (
        id bigint not null auto_increment,
        permission_id bigint not null,
        role_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table sys_user (
        id bigint not null auto_increment,
        administrator bit not null,
        built_in_administrator bit not null,
        display_name varchar(128) not null,
        enabled bit not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    create table sys_user_role (
        id bigint not null auto_increment,
        role_id bigint not null,
        user_id bigint not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

    alter table admin_department
       add constraint UK57oc6g5uk85cdgqtatsqmbyhv unique (user_id, department_id);

    alter table application_access
       add constraint UKj2we0sa9ch7ljfmv3aotf55r9 unique (client_id);

    alter table approval_record
       add constraint UKpt4rn6joxmxcogk6v4022qbpk unique (request_id);

    create index IDXlcphfk8jb5y4hlq50olc6lu4w
       on audit_event (actor_id);

    create index IDXcpavuweh109s8eh7kmj2tl8le
       on audit_event (created_at);

    alter table biz_system
       add constraint uk_biz_system_code unique (code);

    alter table company_import_batch
       add constraint UK87j0tyk1yet1fl3l71rs2r71x unique (fingerprint);

    alter table data_dictionary
       add constraint UK2cqn1kf81j471i21fvnyo5hvw unique (category, code);

    alter table department_permission
       add constraint UKphu6a7c2ysbg6eh2ab5xlb69w unique (department_id, permission_id);

    alter table employee_profile
       add constraint UKsrjtyw0nd6pntwdwn0p9eoaw unique (user_id);

    alter table hr_identity
       add constraint UKiyvee83xvcg9jufig593mvcvl unique (kind, external_id);

    alter table integration_event
       add constraint UKiwdjcm3rcfqnuw11pb9lmdtxn unique (source, request_id);

    alter table permission_detail
       add constraint UK3cxi10wl8vbdr0j3l927w1xbl unique (permission_id);

    alter table permission_template
       add constraint UKr1hodphoqrpwtk4n38r3tyst3 unique (name, permission_id);

    alter table sync_failure
       add constraint UKs890of9ec5a78tcg2q54kmg1i unique (source, request_id);

    alter table sys_account
       add constraint uk_sys_account_username unique (username);

    alter table sys_department
       add constraint UK3c0icpjqmnbs7vsjqwg3cpff3 unique (name);

    alter table sys_function
       add constraint UKrer4q31b0fre2c4r574b801xf unique (module_id, name);

    create index idx_login_session_user
       on sys_login_session (user_id);

    create index idx_login_session_expires
       on sys_login_session (expires_at);

    alter table sys_module
       add constraint UK9mv0uyqx1xn4h0jvp8xj019p unique (system_id, name);

    alter table sys_permission
       add constraint uk_sys_permission_code unique (code);

    alter table sys_post
       add constraint UKhfsq7ug433qblsbuvnh5e6au7 unique (name);

    alter table sys_post
       add constraint UK9kaiw6jbacokvsxcu5ho5bulv unique (role_id);

    alter table sys_role
       add constraint uk_sys_role_code unique (code);

    alter table sys_role_permission
       add constraint uk_sys_role_permission unique (role_id, permission_id);

    alter table sys_user_role
       add constraint uk_sys_user_role unique (user_id, role_id);

    alter table admin_department
       add constraint FKmd6a82u0hy505we7ucf2hxg6
       foreign key (department_id)
       references sys_department (id);

    alter table admin_department
       add constraint FK1iw0ivvicrdsp74fsoes05hm4
       foreign key (user_id)
       references sys_user (id);

    alter table approval_record
       add constraint FKb2t2lsv30t58mdjx54fmbuuey
       foreign key (role_id)
       references sys_role (id);

    alter table approval_record
       add constraint FKejmohhvf8j0n4yif4g23vo32j
       foreign key (user_id)
       references sys_user (id);

    alter table department_permission
       add constraint FK4x2fvg0js6mfa90tg9utk296i
       foreign key (department_id)
       references sys_department (id);

    alter table department_permission
       add constraint FK2iix1ku3dhnbxhnbfhmqjbixt
       foreign key (permission_id)
       references sys_permission (id);

    alter table employee_profile
       add constraint FKrqjdd4pg6nbfgt6t9i2ck2vbl
       foreign key (department_id)
       references sys_department (id);

    alter table employee_profile
       add constraint FKgw10lu8sueobv745furtk3bxb
       foreign key (post_id)
       references sys_post (id);

    alter table employee_profile
       add constraint FK7ot8w0jy7ncdwkaeqvptbgqjd
       foreign key (user_id)
       references sys_user (id);

    alter table managed_role
       add constraint FKhdva69kuok6jjnc8jusfbeiq3
       foreign key (department_id)
       references sys_department (id);

    alter table permission_detail
       add constraint FK6g896nbj58ax2hnn3m30kdwv0
       foreign key (function_id)
       references sys_function (id);

    alter table permission_detail
       add constraint FKqfht613r7odago2qdsjtqf3pm
       foreign key (permission_id)
       references sys_permission (id);

    alter table permission_template
       add constraint FK1uuh6r7ds8e97ux5mwfg8p2ny
       foreign key (permission_id)
       references sys_permission (id);

    alter table sys_account
       add constraint FK6kav8vlbhwmchf475uvbi6tso
       foreign key (user_id)
       references sys_user (id);

    alter table sys_department
       add constraint FKjbbs6toe081i958bo3dea0wvk
       foreign key (manager_id)
       references sys_user (id);

    alter table sys_department
       add constraint FK5bcsdbqycjdgdvti7a55b3ht1
       foreign key (parent_id)
       references sys_department (id);

    alter table sys_function
       add constraint FK4h2u7s7iaxbc6xe5bjx7js898
       foreign key (module_id)
       references sys_module (id);

    alter table sys_login_session
       add constraint FK10cwq4r74wmyhsryx12xjce95
       foreign key (user_id)
       references sys_user (id);

    alter table sys_module
       add constraint FK5j1nc29wi08d8wbimntxk90ww
       foreign key (system_id)
       references biz_system (id);

    alter table sys_permission
       add constraint FKg2hnxo08a5wiprti1lp5d3a32
       foreign key (system_id)
       references biz_system (id);

    alter table sys_post
       add constraint FKjfpb3no7elnlin0vwqbx940gu
       foreign key (role_id)
       references sys_role (id);

    alter table sys_role_permission
       add constraint FKomxrs8a388bknvhjokh440waq
       foreign key (permission_id)
       references sys_permission (id);

    alter table sys_role_permission
       add constraint FK9q28ewrhntqeipl1t04kh1be7
       foreign key (role_id)
       references sys_role (id);

    alter table sys_user_role
       add constraint FKhh52n8vd4ny9ff4x9fb8v65qx
       foreign key (role_id)
       references sys_role (id);

    alter table sys_user_role
       add constraint FKb40xxfch70f5qnyfw8yme1n1s
       foreign key (user_id)
       references sys_user (id);

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
