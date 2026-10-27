/**
 * 三份 XLSX 的组织数据导入：解析 → 预览校验 → 确认 → 单事务写入组织、员工和功能目录。
 * CompanyData 描述导入数据实体；CompanyImportService 编排规则；CompanyController 提供 HTTP 入口。
 * 岗位角色和权限模板只是配置来源，导入不会自动给员工授权。
 */
package com.cdwy.permission.company;
