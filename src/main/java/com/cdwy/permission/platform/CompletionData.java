package com.cdwy.permission.platform;

import jakarta.persistence.*;
import java.time.Instant;
import com.cdwy.permission.company.CompanyData;

/** Persistent records for application onboarding, review and HR reconciliation. */
public final class CompletionData {
    private CompletionData() {}
    @Entity @Table(name="application_access") public static class ApplicationAccess {
        @Id public Long systemId;
        @Column(unique=true,nullable=false,length=100) public String clientId;
        @Column(nullable=false,length=2048) public String entryUrl;
        @Column(nullable=false,length=16) public String protocol="OIDC";
        @Column(nullable=false,length=16) public String status="DRAFT";
        @Column(length=2000) public String compatibilityPlan;
        @Column(length=2000) public String acceptance;
    }
    @Entity @Table(name="platform_signing_key") public static class SigningKey {
        @Id @Column(length=64) public String id;
        @Column(nullable=false,length=8000) public String jwk;
    }
    @Entity @Table(name="permission_review") public static class Review extends CompanyData.Row {
        @Column(nullable=false) public Long actorId;
        @Column(nullable=false) public Long userId;
        @Column(nullable=false) public Long roleId;
        @Column(nullable=false,length=16) public String decision;
        @Column(nullable=false,length=1000) public String reason;
        @Column(nullable=false) public Instant createdAt;
    }
    @Entity @Table(name="audit_archive") public static class Archive {
        @Id public Long id;
        public Long actorId; public Long departmentId; public Long targetDepartmentId;
        @Column(nullable=false,length=64) public String kind;
        @Column(nullable=false,length=255) public String target;
        @Column(nullable=false,length=32) public String result;
        @Column(nullable=false) public Instant createdAt;
        @Column(nullable=false) public Instant archivedAt;
    }
    @Entity @Table(name="hr_identity",uniqueConstraints=@UniqueConstraint(columnNames={"kind","external_id"}))
    public static class HrIdentity extends CompanyData.Row {
        @Column(nullable=false,length=16) public String kind;
        @Column(nullable=false,length=128) public String externalId;
        @Column(nullable=false) public Long localId;
    }
    @Entity @Table(name="sync_failure",uniqueConstraints=@UniqueConstraint(columnNames={"source","request_id"}))
    public static class Failure extends CompanyData.Row {
        @Column(nullable=false,length=16) public String source;
        @Column(nullable=false,length=64) public String requestId;
        @Column(nullable=false,length=64) public String digest;
        @Column(nullable=false,length=1000) public String error;
        @Column(nullable=false,length=16) public String status="OPEN";
        @Column(length=1000) public String resolution;
        @Column(nullable=false) public Instant createdAt;
    }
    @Entity @Table(name="platform_notification") public static class Notification extends CompanyData.Row {
        @Column(nullable=false) public Long departmentId;
        @Column(nullable=false) public Long userId;
        @Column(nullable=false,length=64) public String kind;
        @Column(nullable=false,length=1000) public String message;
        @Column(nullable=false) public Instant createdAt;
    }
}
