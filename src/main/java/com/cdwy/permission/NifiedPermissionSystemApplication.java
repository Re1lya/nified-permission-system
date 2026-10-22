package com.cdwy.permission;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession(maxInactiveIntervalInSeconds=1800)
@SpringBootApplication
public class NifiedPermissionSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(NifiedPermissionSystemApplication.class, args);
	}

}
