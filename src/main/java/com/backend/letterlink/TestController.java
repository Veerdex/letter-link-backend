package com.backend.letterlink;

import org.springframework.web.bind.annotation.*;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

@RestController
public class TestController {

    @GetMapping("/test")
    public String test() {
        return "Backend is running";
    }

    @GetMapping("/players/register")
    public String register(@RequestParam String username) {

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement()) {

            // Create table if not exists
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    id TEXT PRIMARY KEY,
                    username TEXT UNIQUE,
                    rating INTEGER DEFAULT 1000
                )
            """);

            String id = UUID.randomUUID().toString();

            stmt.executeUpdate(
                    "INSERT INTO players (id, username) VALUES ('" + id + "', '" + username + "')"
            );

            System.out.println("Saved player: " + username);

        } catch (Exception e) {
            e.printStackTrace();
            return "Error saving player";
        }

        return "Registered: " + username;
    }
}