/**
 * 接口数据契约：Request 声明允许输入的字段及格式约束，Response 声明对外输出的字段。
 * 部分 DTO 使用 CloneFactory 的编译期映射；字段复制不替代业务校验或授权校验。
 * 例如创建普通账号不接收 administrator 字段，用户响应不包含 passwordHash。
 */
package com.cdwy.permission.dto;
