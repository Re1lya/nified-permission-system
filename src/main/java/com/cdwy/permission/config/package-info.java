/**
 * 横切配置：依赖装配、密码编码、管理员初始化及 MVC API 访问拦截。
 * API 拦截器负责通用登录和默认管理边界；平台模块仍要逐接口判断部门、目标对象和授权范围。
 * OIDC 的 SecurityFilterChain 定义在 platform.OidcConfig，阅读安全链时应一起查看。
 */
package com.cdwy.permission.config;
