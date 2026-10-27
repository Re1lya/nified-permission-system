package com.cdwy.permission.dto;

import cn.edu.xmu.clonefactory.CopyFrom;
import com.cdwy.permission.entity.UserAccount;

/**
 * 输出白名单：只暴露客户端需要的账号信息，不含 passwordHash。
 * CloneFactory 按映射复制字段，领域对象与 API 返回格式因此可以分别维护。
 */
@CopyFrom(UserAccount.class)
public class UserResponse {

    private Long id;
    private String username;
    private String displayName;
    private boolean enabled;
    private boolean administrator;

    public boolean isAdministrator() { return administrator; }
    public void setAdministrator(boolean administrator) { this.administrator = administrator; }

    public UserResponse() {
    }

    public UserResponse(Long id, String username, String displayName, boolean enabled) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.enabled = enabled;
    }

    public Long id() {
        return id;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String username() {
        return username;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String displayName() {
        return displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
