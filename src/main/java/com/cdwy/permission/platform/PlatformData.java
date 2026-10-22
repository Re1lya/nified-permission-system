package com.cdwy.permission.platform;

import jakarta.persistence.*;
import java.time.Instant;
import com.cdwy.permission.company.CompanyData;
import com.cdwy.permission.entity.*;

public final class PlatformData {
    private PlatformData() {}
    @Entity @Table(name="admin_department",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","department_id"}))
    public static class AdminDepartment extends CompanyData.Row {
        @ManyToOne(optional=false) public UserAccount user;
        @ManyToOne(optional=false) public CompanyData.Department department;
    }
    @Entity @Table(name="account_policy") public static class Policy {
        @Id public Long userId;
        @Column(nullable=false,length=24) public String personType="EMPLOYEE";
        public Instant expiresAt;
        @Column(nullable=false) public boolean mustChangePassword;
        @Column(nullable=false) public long version;
        @Column(nullable=false) public boolean deleted;
    }
    @Entity @Table(name="login_attempt") public static class Attempt {
        @Id @Column(length=64) public String username;
        public int failures; public Instant lockedUntil;
    }
    @Entity @Table(name="audit_event",indexes={@Index(columnList="actorId"),@Index(columnList="createdAt")} )
    public static class Audit extends CompanyData.Row {
        public Long actorId; public Long departmentId;
        /** Department of the affected user, so department admins also see changes made to their staff. */
        public Long targetDepartmentId;
        @Column(nullable=false,length=64) public String kind;
        @Column(nullable=false,length=255) public String target;
        @Column(nullable=false,length=32) public String result;
        @Column(nullable=false) public Instant createdAt;
    }
    @Entity @Table(name="integration_event",uniqueConstraints=@UniqueConstraint(columnNames={"source","request_id"}))
    public static class Integration extends CompanyData.Row {
        @Column(nullable=false,length=16) public String source;
        @Column(nullable=false,length=64) public String requestId;
        @Column(nullable=false,length=64) public String digest;
        @Column(nullable=false,length=32) public String result;
        @Column(nullable=false) public Instant createdAt;
        public Long userId;
    }
    @Entity @Table(name="approval_record") public static class Approval extends CompanyData.Row {
        @Column(nullable=false,unique=true,length=64) public String requestId;
        @ManyToOne(optional=false) public UserAccount user;
        @ManyToOne(optional=false) public Role role;
        @Column(nullable=false,length=16) public String decision;
        @Column(nullable=false) public Instant createdAt;
    }
    @Entity @Table(name="data_dictionary",uniqueConstraints=@UniqueConstraint(columnNames={"category","code"}))
    public static class Dictionary extends CompanyData.Row {
        @Column(nullable=false,length=64) public String category;
        @Column(nullable=false,length=64) public String code;
        @Column(nullable=false,length=128) public String name;
    }
    @Entity @Table(name="managed_role") public static class ManagedRole {
        @Id public Long roleId;
        @ManyToOne(optional=false) public CompanyData.Department department;
    }
    @Entity @Table(name="department_permission",uniqueConstraints=@UniqueConstraint(columnNames={"department_id","permission_id"}))
    public static class GrantLimit extends CompanyData.Row {
        @ManyToOne(optional=false) public CompanyData.Department department;
        @ManyToOne(optional=false) public Permission permission;
    }
    @Entity @Table(name="session_activity") public static class Activity {
        @Id @Column(length=36) public String sessionId;
        @Column(nullable=false) public Instant lastSeen;
    }
}
