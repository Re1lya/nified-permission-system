package com.cdwy.permission;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动入口：自动扫描本包及子包，装配 Web、JPA、数据源和业务组件。
 * 定时任务用于到期提醒等后台工作；JDBC HTTP Session 服务于浏览器 SSO，
 * 与 JwtTokenService 使用的 sys_login_session（平台 JWT 会话）是两套不同记录。
 */
@org.springframework.scheduling.annotation.EnableScheduling
@org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession(maxInactiveIntervalInSeconds=1800)
@SpringBootApplication
public class NifiedPermissionSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(NifiedPermissionSystemApplication.class, args);
	}

}
