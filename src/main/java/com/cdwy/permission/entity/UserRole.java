package com.cdwy.permission.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 用户—角色关联实体：一个用户可有多个角色，一个角色可分配给多个用户。
 * 两条 ManyToOne 关系形成多对多；(user_id, role_id) 唯一约束让相同授权只保存一次。
 * LAZY 延迟读取关联对象；需要属性时应在事务内访问并转换为响应 DTO。
 */
@Entity
@Table(name = "sys_user_role", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sys_user_role", columnNames = {"user_id", "role_id"})
})
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    protected UserRole() {
    }

    public UserRole(UserAccount user, Role role) {
        this.user = user;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public Role getRole() {
        return role;
    }
}
