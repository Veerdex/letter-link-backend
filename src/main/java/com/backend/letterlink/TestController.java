package com.backend.letterlink;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

    @GetMapping("/test")
    public String test() {
        System.out.println("Test endpoint was hit.");
        return "Backend is running";
    }

    @GetMapping("/players/register")
    public String register(@RequestParam String username) {
        System.out.println("Register endpoint hit with username: " + username);
        return "Registered: " + username;
    }
}