-- Schema SQL for a new isolated database. No data or credentials.
SET time_zone = '+00:00';

CREATE TABLE `account_policy` (
    `deleted` BOOLEAN NOT NULL,
    `must_change_password` BOOLEAN NOT NULL,
    `expires_at` TIMESTAMP(6),
    `user_id` BIGINT NOT NULL,
    `version` BIGINT NOT NULL,
    `person_type` VARCHAR(24) NOT NULL
,
    PRIMARY KEY(`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `admin_department` (
    `department_id` BIGINT NOT NULL,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `user_id` BIGINT NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `approval_record` (
    `created_at` TIMESTAMP(6) NOT NULL,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `role_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `decision` VARCHAR(16) NOT NULL,
    `request_id` VARCHAR(64) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `audit_event` (
    `actor_id` BIGINT,
    `created_at` TIMESTAMP(6) NOT NULL,
    `department_id` BIGINT,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `target_department_id` BIGINT,
    `result` VARCHAR(32) NOT NULL,
    `kind` VARCHAR(64) NOT NULL,
    `target` VARCHAR(255) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `biz_system` (
    `enabled` BOOLEAN NOT NULL,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `code` VARCHAR(64) NOT NULL,
    `name` VARCHAR(128) NOT NULL,
    `base_url` VARCHAR(255)
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `company_import_batch` (
    `departments` INTEGER NOT NULL,
    `employees` INTEGER NOT NULL,
    `functions` INTEGER NOT NULL,
    `actor_id` BIGINT NOT NULL,
    `created_at` TIMESTAMP(6) NOT NULL,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `fingerprint` VARCHAR(64) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `data_dictionary` (
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `category` VARCHAR(64) NOT NULL,
    `code` VARCHAR(64) NOT NULL,
    `name` VARCHAR(128) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `department_permission` (
    `department_id` BIGINT NOT NULL,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `permission_id` BIGINT NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `employee_profile` (
    `department_id` BIGINT NOT NULL,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `post_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `phone` VARCHAR(32) NOT NULL,
    `company` VARCHAR(128) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `integration_event` (
    `created_at` TIMESTAMP(6) NOT NULL,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `user_id` BIGINT,
    `source` VARCHAR(16) NOT NULL,
    `result` VARCHAR(32) NOT NULL,
    `digest` VARCHAR(64) NOT NULL,
    `request_id` VARCHAR(64) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `login_attempt` (
    `failures` INTEGER NOT NULL,
    `locked_until` TIMESTAMP(6),
    `username` VARCHAR(64) NOT NULL
,
    PRIMARY KEY(`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `managed_role` (
    `department_id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL
,
    PRIMARY KEY(`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `permission_detail` (
    `function_id` BIGINT NOT NULL,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `permission_id` BIGINT NOT NULL,
    `action` VARCHAR(32) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `permission_template` (
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `permission_id` BIGINT NOT NULL,
    `name` VARCHAR(64) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `session_activity` (
    `last_seen` TIMESTAMP(6) NOT NULL,
    `session_id` VARCHAR(36) NOT NULL
,
    PRIMARY KEY(`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `sys_department` (
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `manager_id` BIGINT,
    `parent_id` BIGINT,
    `manager_name` VARCHAR(128),
    `name` VARCHAR(128) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `sys_function` (
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `module_id` BIGINT NOT NULL,
    `name` VARCHAR(128) NOT NULL,
    `description` VARCHAR(2000) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `sys_login_session` (
    `expires_at` TIMESTAMP(6) NOT NULL,
    `issued_at` TIMESTAMP(6) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `id` VARCHAR(36) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `sys_module` (
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `system_id` BIGINT NOT NULL,
    `name` VARCHAR(128) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `sys_permission` (
    `active` BOOLEAN NOT NULL,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `system_id` BIGINT NOT NULL,
    `type` VARCHAR(16) NOT NULL,
    `code` VARCHAR(128) NOT NULL,
    `name` VARCHAR(128) NOT NULL,
    `menu_path` VARCHAR(255) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `sys_post` (
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `role_id` BIGINT NOT NULL,
    `name` VARCHAR(128) NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `sys_role` (
    `enabled` BOOLEAN NOT NULL,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `code` VARCHAR(64) NOT NULL,
    `name` VARCHAR(128) NOT NULL,
    `description` VARCHAR(255)
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `sys_role_permission` (
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `permission_id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `sys_user` (
    `administrator` BOOLEAN NOT NULL,
    `enabled` BOOLEAN NOT NULL,
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `username` VARCHAR(64) NOT NULL,
    `display_name` VARCHAR(128) NOT NULL,
    `password_hash` VARCHAR(255)
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `sys_user_role` (
    `id` BIGINT AUTO_INCREMENT NOT NULL,
    `role_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL
,
    PRIMARY KEY(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE `data_dictionary` ADD CONSTRAINT `constraint_e71` UNIQUE (`category`, `code`);

ALTER TABLE `permission_detail` ADD CONSTRAINT `constraint_a` UNIQUE (`permission_id`);

ALTER TABLE `sys_department` ADD CONSTRAINT `constraint_3` UNIQUE (`name`);

ALTER TABLE `company_import_batch` ADD CONSTRAINT `constraint_7` UNIQUE (`fingerprint`);

ALTER TABLE `permission_template` ADD CONSTRAINT `constraint_96` UNIQUE (`name`, `permission_id`);

ALTER TABLE `approval_record` ADD CONSTRAINT `constraint_1` UNIQUE (`request_id`);

ALTER TABLE `sys_role` ADD CONSTRAINT `uk_sys_role_code` UNIQUE (`code`);

ALTER TABLE `sys_post` ADD CONSTRAINT `constraint_a5` UNIQUE (`role_id`);

ALTER TABLE `sys_role_permission` ADD CONSTRAINT `uk_sys_role_permission` UNIQUE (`role_id`, `permission_id`);

ALTER TABLE `sys_user_role` ADD CONSTRAINT `uk_sys_user_role` UNIQUE (`user_id`, `role_id`);

ALTER TABLE `sys_user` ADD CONSTRAINT `uk_sys_user_username` UNIQUE (`username`);

ALTER TABLE `biz_system` ADD CONSTRAINT `uk_biz_system_code` UNIQUE (`code`);

ALTER TABLE `employee_profile` ADD CONSTRAINT `constraint_c` UNIQUE (`user_id`);

ALTER TABLE `sys_permission` ADD CONSTRAINT `uk_sys_permission_code` UNIQUE (`code`);

ALTER TABLE `integration_event` ADD CONSTRAINT `constraint_c54` UNIQUE (`source`, `request_id`);

ALTER TABLE `department_permission` ADD CONSTRAINT `constraint_28` UNIQUE (`department_id`, `permission_id`);

ALTER TABLE `sys_post` ADD CONSTRAINT `constraint_a50` UNIQUE (`name`);

ALTER TABLE `sys_module` ADD CONSTRAINT `constraint_96ea` UNIQUE (`system_id`, `name`);

ALTER TABLE `sys_function` ADD CONSTRAINT `constraint_fc4` UNIQUE (`module_id`, `name`);

ALTER TABLE `admin_department` ADD CONSTRAINT `constraint_ff` UNIQUE (`user_id`, `department_id`);

ALTER TABLE `sys_user_role` ADD CONSTRAINT `fkhh52n8vd4ny9ff4x9fb8v65qx` FOREIGN KEY(`role_id`) REFERENCES `sys_role`(`id`);

ALTER TABLE `department_permission` ADD CONSTRAINT `fk4x2fvg0js6mfa90tg9utk296i` FOREIGN KEY(`department_id`) REFERENCES `sys_department`(`id`);

ALTER TABLE `employee_profile` ADD CONSTRAINT `fkgw10lu8sueobv745furtk3bxb` FOREIGN KEY(`post_id`) REFERENCES `sys_post`(`id`);

ALTER TABLE `managed_role` ADD CONSTRAINT `fkhdva69kuok6jjnc8jusfbeiq3` FOREIGN KEY(`department_id`) REFERENCES `sys_department`(`id`);

ALTER TABLE `sys_department` ADD CONSTRAINT `fk5bcsdbqycjdgdvti7a55b3ht1` FOREIGN KEY(`parent_id`) REFERENCES `sys_department`(`id`);

ALTER TABLE `permission_template` ADD CONSTRAINT `fk1uuh6r7ds8e97ux5mwfg8p2ny` FOREIGN KEY(`permission_id`) REFERENCES `sys_permission`(`id`);

ALTER TABLE `sys_role_permission` ADD CONSTRAINT `fkomxrs8a388bknvhjokh440waq` FOREIGN KEY(`permission_id`) REFERENCES `sys_permission`(`id`);

ALTER TABLE `permission_detail` ADD CONSTRAINT `fk6g896nbj58ax2hnn3m30kdwv0` FOREIGN KEY(`function_id`) REFERENCES `sys_function`(`id`);

ALTER TABLE `approval_record` ADD CONSTRAINT `fkb2t2lsv30t58mdjx54fmbuuey` FOREIGN KEY(`role_id`) REFERENCES `sys_role`(`id`);

ALTER TABLE `sys_permission` ADD CONSTRAINT `fkg2hnxo08a5wiprti1lp5d3a32` FOREIGN KEY(`system_id`) REFERENCES `biz_system`(`id`);

ALTER TABLE `department_permission` ADD CONSTRAINT `fk2iix1ku3dhnbxhnbfhmqjbixt` FOREIGN KEY(`permission_id`) REFERENCES `sys_permission`(`id`);

ALTER TABLE `sys_department` ADD CONSTRAINT `fkjbbs6toe081i958bo3dea0wvk` FOREIGN KEY(`manager_id`) REFERENCES `sys_user`(`id`);

ALTER TABLE `employee_profile` ADD CONSTRAINT `fkrqjdd4pg6nbfgt6t9i2ck2vbl` FOREIGN KEY(`department_id`) REFERENCES `sys_department`(`id`);

ALTER TABLE `sys_function` ADD CONSTRAINT `fk4h2u7s7iaxbc6xe5bjx7js898` FOREIGN KEY(`module_id`) REFERENCES `sys_module`(`id`);

ALTER TABLE `sys_module` ADD CONSTRAINT `fk5j1nc29wi08d8wbimntxk90ww` FOREIGN KEY(`system_id`) REFERENCES `biz_system`(`id`);

ALTER TABLE `admin_department` ADD CONSTRAINT `fkmd6a82u0hy505we7ucf2hxg6` FOREIGN KEY(`department_id`) REFERENCES `sys_department`(`id`);

ALTER TABLE `admin_department` ADD CONSTRAINT `fk1iw0ivvicrdsp74fsoes05hm4` FOREIGN KEY(`user_id`) REFERENCES `sys_user`(`id`);

ALTER TABLE `employee_profile` ADD CONSTRAINT `fk7ot8w0jy7ncdwkaeqvptbgqjd` FOREIGN KEY(`user_id`) REFERENCES `sys_user`(`id`);

ALTER TABLE `approval_record` ADD CONSTRAINT `fkejmohhvf8j0n4yif4g23vo32j` FOREIGN KEY(`user_id`) REFERENCES `sys_user`(`id`);

ALTER TABLE `sys_post` ADD CONSTRAINT `fkjfpb3no7elnlin0vwqbx940gu` FOREIGN KEY(`role_id`) REFERENCES `sys_role`(`id`);

ALTER TABLE `sys_login_session` ADD CONSTRAINT `fk10cwq4r74wmyhsryx12xjce95` FOREIGN KEY(`user_id`) REFERENCES `sys_user`(`id`);

ALTER TABLE `sys_user_role` ADD CONSTRAINT `fkb40xxfch70f5qnyfw8yme1n1s` FOREIGN KEY(`user_id`) REFERENCES `sys_user`(`id`);

ALTER TABLE `permission_detail` ADD CONSTRAINT `fkqfht613r7odago2qdsjtqf3pm` FOREIGN KEY(`permission_id`) REFERENCES `sys_permission`(`id`);

ALTER TABLE `sys_role_permission` ADD CONSTRAINT `fk9q28ewrhntqeipl1t04kh1be7` FOREIGN KEY(`role_id`) REFERENCES `sys_role`(`id`);

CREATE INDEX `idxlcphfk8jb5y4hlq50olc6lu4w` ON `audit_event`(`actor_id` NULLS FIRST);

CREATE INDEX `idxcpavuweh109s8eh7kmj2tl8le` ON `audit_event`(`created_at` NULLS FIRST);

CREATE INDEX `idx_login_session_user` ON `sys_login_session`(`user_id` NULLS FIRST);

CREATE INDEX `idx_login_session_expires` ON `sys_login_session`(`expires_at` NULLS FIRST);
