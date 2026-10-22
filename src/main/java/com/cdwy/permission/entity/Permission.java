package com.cdwy.permission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "sys_permission", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sys_permission_code", columnNames = "code")
})
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 255)
    private String menuPath;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "system_id", nullable = false)
    private BusinessSystem system;

    /** MENU, BUTTON or API (requirement 6.2); a button and its API may share one code. */
    @Column(nullable = false, length = 16)
    private String type = "API";

    /** Retired permissions are marked inactive instead of deleted (UC-B03): grants stay, but no longer take effect. */
    @Column(nullable = false)
    private boolean active = true;

    protected Permission() {
    }

    public Permission(String code, String name, String menuPath, BusinessSystem system) {
        this.code = code;
        this.name = name;
        this.menuPath = menuPath;
        this.system = system;
    }

    public Permission(String code, String name, String menuPath, BusinessSystem system, String type) {
        this(code, name, menuPath, system);
        this.type = type;
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

    public String getMenuPath() {
        return menuPath;
    }

    public BusinessSystem getSystem() {
        return system;
    }

    public Long getSystemId() {
        return system == null ? null : system.getId();
    }

    public String getSystemCode() {
        return system == null ? null : system.getCode();
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setMenuPath(String menuPath) {
        this.menuPath = menuPath;
    }

    public void setSystem(BusinessSystem system) {
        this.system = system;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isActive() {
        return active;
    }

    /** CloneFactory copies through get-prefixed accessors, as UserAccount.getEnabled does. */
    public boolean getActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
