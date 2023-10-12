package com.xndp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.xndp.mapper")
@SpringBootApplication
public class XnDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(XnDianPingApplication.class, args);
    }

}

