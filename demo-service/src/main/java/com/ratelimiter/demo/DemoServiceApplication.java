package com.ratelimiter.demo;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Trivial backend that sits behind the gateway and echoes what it received. */
@SpringBootApplication
@RestController
public class DemoServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoServiceApplication.class, args);
    }

    @RequestMapping("/**")
    public Map<String, String> echo(HttpServletRequest request) {
        return Map.of("method", request.getMethod(), "path", request.getRequestURI());
    }
}
