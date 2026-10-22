package com.cdwy.permission.dto;

import cn.edu.xmu.clonefactory.CopyFrom;
import com.cdwy.permission.entity.Permission;

@CopyFrom(Permission.class)
public class PermissionResponse {

    private Long id;
    private Long systemId;
    private String systemCode;
    private String code;
    private String name;
    private String menuPath;
    private String type;
    private boolean active;

    public PermissionResponse() {
    }

    public PermissionResponse(Long id, Long systemId, String systemCode, String code, String name, String menuPath) {
        this.id = id;
        this.systemId = systemId;
        this.systemCode = systemCode;
        this.code = code;
        this.name = name;
        this.menuPath = menuPath;
    }

    public Long id() { return id; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long systemId() { return systemId; }
    public Long getSystemId() { return systemId; }
    public void setSystemId(Long systemId) { this.systemId = systemId; }
    public String systemCode() { return systemCode; }
    public String getSystemCode() { return systemCode; }
    public void setSystemCode(String systemCode) { this.systemCode = systemCode; }
    public String code() { return code; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String name() { return name; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String menuPath() { return menuPath; }
    public String getMenuPath() { return menuPath; }
    public void setMenuPath(String menuPath) { this.menuPath = menuPath; }
    public String type() { return type; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public boolean active() { return active; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
