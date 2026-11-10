package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("org.example.mapper")
public class Main {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Main.class);
        application.setBanner((environment, sourceClass, out) -> ZelenskyyBannerUtil.print(out));
        application.run(args);
    }
}
