/**
 * 平台扩展模块：部门范围、账号生命周期、HR/OA 集成、OIDC、初始密码及审计。
 * 部分文件同时包含 Controller 和包内 Service；部分控制器以 @Transactional 直接编排 JdbcTemplate。
 * PlatformStore 汇集 SQL 访问与公共范围校验；它既有持久化职责也有业务辅助职责，并非纯 Repository。
 * 更细的分层是可演进方向，说明现状时应按实际调用链区分。
 */
package com.cdwy.permission.platform;
