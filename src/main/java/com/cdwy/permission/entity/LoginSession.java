package com.cdwy.permission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "sys_login_session", indexes = {
        @Index(name = "idx_login_session_user", columnList = "user_id"),
        @Index(name = "idx_login_session_expires", columnList = "expires_at")
})
public class LoginSession {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    protected LoginSession() {
    }

    public LoginSession(String id, UserAccount user, Instant issuedAt, Instant expiresAt) {
        this.id = id;
        this.user = user;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public String getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
