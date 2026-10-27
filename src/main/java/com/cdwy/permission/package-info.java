/**
 * 应用入口：单体 Spring Boot 应用，按职责划分核心 RBAC、组织导入和平台扩展模块。
 * 典型请求路径是 Controller → Service → Repository/EntityManager 或 JdbcTemplate → 数据库。
 * platform 包部分控制器同时编排业务与 SQL；这是现有实现的边界，不是独立微服务。
 */
package com.cdwy.permission;
