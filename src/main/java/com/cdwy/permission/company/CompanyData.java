package com.cdwy.permission.company;

import jakarta.persistence.*;
import java.time.Instant;

/** Import-owned master data is separate from credentials and live grants. */
/**
 * 组织结构与授权结构分开建模：Department.parent 是组织树，不是角色继承。
 * Post.role 一一对应岗位角色，但 Employee.post 本身不会产生 UserRole 授权。
 * 功能目录按系统 → 模块 → 功能 → 操作展开，Template 是授权候选来源，不是生效授权。
 * SQLRestriction 作用于 Hibernate 实体查询；直接 JdbcTemplate 查询仍须显式处理 deleted 条件。
 */
public final class CompanyData {
    private CompanyData() {}
    @MappedSuperclass public static class Row {
        @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    }
    @Entity @Table(name="sys_department") @org.hibernate.annotations.SQLRestriction("deleted=false") public static class Department extends Row {
        @Column(nullable=false) public boolean deleted;
        @Column(nullable=false,unique=true,length=128) public String name;
        @ManyToOne public Department parent;
        @Column(length=128) public String managerName;
        @ManyToOne public com.cdwy.permission.entity.UserAccount manager;
    }
    @Entity @Table(name="sys_post") @org.hibernate.annotations.SQLRestriction("deleted=false") public static class Post extends Row {
        @Column(nullable=false) public boolean deleted;
        @Column(nullable=false,unique=true,length=128) public String name;
        @OneToOne(optional=false) @JoinColumn(unique=true) public com.cdwy.permission.entity.Role role;
    }
    @Entity @Table(name="employee_profile") public static class Employee extends Row {
        @OneToOne(optional=false) @JoinColumn(unique=true) public com.cdwy.permission.entity.UserAccount user;
        @ManyToOne(optional=false) public Department department;
        @ManyToOne(optional=false) public Post post;
        @Column(nullable=false,length=32) public String phone;
        @Column(nullable=false,length=128) public String company;
    }
    @Entity @Table(name="sys_module",uniqueConstraints=@UniqueConstraint(columnNames={"system_id","name"}))
    public static class Module extends Row {
        @ManyToOne(optional=false) public com.cdwy.permission.entity.BusinessSystem system;
        @Column(nullable=false,length=128) public String name;
    }
    @Entity @Table(name="sys_function",uniqueConstraints=@UniqueConstraint(columnNames={"module_id","name"}))
    public static class Function extends Row {
        @ManyToOne(optional=false) public Module module;
        @Column(nullable=false,length=128) public String name;
        @Column(nullable=false,length=2000) public String description;
    }
    @Entity @Table(name="permission_detail") public static class Detail extends Row {
        @OneToOne(optional=false) @JoinColumn(unique=true) public com.cdwy.permission.entity.Permission permission;
        @ManyToOne(optional=false) public Function function;
        @Column(nullable=false,length=32) public String action;
    }
    @Entity @Table(name="permission_template",uniqueConstraints=@UniqueConstraint(columnNames={"name","permission_id"}))
    public static class Template extends Row {
        @Column(nullable=false,length=64) public String name;
        @ManyToOne(optional=false) public com.cdwy.permission.entity.Permission permission;
    }
    @Entity @Table(name="company_import_batch") public static class Batch extends Row {
        @Column(nullable=false,unique=true,length=64) public String fingerprint;
        @Column(nullable=false) public Instant createdAt;
        @Column(nullable=false) public Long actorId;
        @Column(nullable=false) public int employees;
        @Column(nullable=false) public int departments;
        @Column(nullable=false) public int functions;
    }
}
