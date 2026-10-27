/**
 * 业务用例层：RbacService 维护授权与账号规则，JwtTokenService 管理平台令牌和会话。
 * 构造器注入依赖；写用例通过 Spring 事务代理确定原子性边界，读用例默认只读。
 * 实体主要描述状态与映射，业务规则主要集中在服务中，不能将其描述为完整的富领域模型。
 */
package com.cdwy.permission.service;
