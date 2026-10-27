/**
 * 核心实体的持久化接口：Spring Data JPA 生成常规查询和保存实现，Hibernate 将对象操作转换为 SQL。
 * 派生方法名中的 User_Id 表示关联对象 user 的 id；@Query 中使用实体和属性名的是 JPQL。
 * 业务先判断重复提供友好提示，数据库唯一约束继续承担并发条件下的最终约束。
 */
package com.cdwy.permission.repository;
