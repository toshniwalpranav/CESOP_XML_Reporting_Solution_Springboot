package com.example.cesop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CesopApplication {
    public static void main(String[] args) {
        SpringApplication.run(CesopApplication.class, args);
    }
}
