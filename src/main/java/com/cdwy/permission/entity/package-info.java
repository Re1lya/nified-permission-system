/**
 * 核心对象与数据库映射：用户、角色、权限、业务系统，以及两类授权关联和登录会话。
 * UserRole 和 RolePermission 将多对多关系建模为显式关联实体，联合唯一约束防止重复授权。
 * Entity 是持久化对象，不直接充当公共响应；DTO 避免暴露密码哈希和不必要的对象关联。
 */
package com.cdwy.permission.entity;
