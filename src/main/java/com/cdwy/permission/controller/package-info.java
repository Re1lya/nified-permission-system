/**
 * 核心 RBAC 的 HTTP 适配层：把路径、请求 DTO 和业务方法连接起来，输出响应 DTO。
 * 参数格式交给 Bean Validation，业务规则交给 RbacService，错误状态由 ApiExceptionHandler 统一转换。
 * 接口对应用户、角色、权限点及它们之间的授权关系，不等同于逐张表的 CRUD。
 */
package com.cdwy.permission.controller;
