package com.cdwy.permission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "biz_system", uniqueConstraints = {
        @UniqueConstraint(name = "uk_biz_system_code", columnNames = "code")
})
@org.hibernate.annotations.SQLRestriction("deleted=false")
public class BusinessSystem {
    @Column(nullable=false) private boolean deleted;
    public boolean isDeleted(){return deleted;}
    public void setDeleted(boolean value){deleted=value;}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 255)
    private String baseUrl;

    @Column(nullable = false)
    private boolean enabled = true;

    protected BusinessSystem() {
    }

    public BusinessSystem(String code, String name, String baseUrl) {
        this.code = code;
        this.name = name;
        this.baseUrl = baseUrl;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean getEnabled() {
        return enabled;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
