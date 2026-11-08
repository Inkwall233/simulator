package com.skilllab.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SkillLab 管理后端入口。
 */
@SpringBootApplication
@EnableScheduling
public class SkillLabAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillLabAdminApplication.class, args);
    }
}
