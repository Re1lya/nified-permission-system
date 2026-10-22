package com.cdwy.permission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdatePermissionRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 255) String menuPath,
        @Pattern(regexp = "MENU|BUTTON|API", message = "权限类型只能是 MENU、BUTTON 或 API") String type,
        Boolean active
) {
    public UpdatePermissionRequest(String name, String menuPath) {
        this(name, menuPath, null, null);
    }
}
