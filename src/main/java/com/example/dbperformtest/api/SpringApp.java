package com.example.dbperformtest.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the REST API server.
 *
 * @SpringBootApplication does three things in one annotation:
 *   1. @Configuration       — this class can define @Bean methods
 *   2. @EnableAutoConfiguration — Spring Boot wires Tomcat, Jackson, etc. automatically
 *   3. @ComponentScan       — scans this package and sub-packages for
 *                             @RestController, @Service, @Component, ...
 *
 * @ImportResource loads our XML bean definitions and merges them into
 * the same application context that component scan builds.
 * Result: XML beans and annotation-based beans live together and can
 * inject each other freely.
 */
@SpringBootApplication
public class SpringApp {

    public static void main(String[] args) {
        SpringApplication.run(SpringApp.class, args);
        // Server starts on http://localhost:8080
    }
}
