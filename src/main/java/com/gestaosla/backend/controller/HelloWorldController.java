package com.gestaosla.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/hello-world")
public class HelloWorldController {

    private final String environment;

    public HelloWorldController(
        @Value ("${app.environment}") String environment
    ) {
        this.environment = environment;
    }

    @GetMapping
    public String helloWorld() {
        return "Hello World - Ambiente: " + environment;
    }
}
