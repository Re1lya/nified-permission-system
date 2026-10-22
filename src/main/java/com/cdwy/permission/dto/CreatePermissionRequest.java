package com.cdwy.permission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePermissionRequest(
        @NotNull Long systemId,
        @NotBlank @Size(max = 128) @Pattern(regexp = CreatePermissionRequest.CODE, message = "权限编码格式为 应用:模块:操作，小写字母、数字、下划线或连字符，最多三段") String code,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 255) String menuPath,
        @Pattern(regexp = "MENU|BUTTON|API", message = "权限类型只能是 MENU、BUTTON 或 API") String type
) {
    /** Requirement 6.2: lower case, colon-separated, at most three segments. */
    public static final String CODE = "[a-z0-9][a-z0-9_.-]*(:[a-z0-9][a-z0-9_.-]*){0,2}";

    public CreatePermissionRequest(Long systemId, String code, String name, String menuPath) {
        this(systemId, code, name, menuPath, null);
    }
}
