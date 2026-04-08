package com.backend.letterlink;

import java.sql.Connection;
import java.sql.DriverManager;

public class Database {

    public static Connection getConnection() throws Exception {
        String url = System.getenv("TURSO_DB_URL");
        String token = System.getenv("TURSO_AUTH_TOKEN");

        if (url == null || url.isBlank()) {
            throw new RuntimeException("TURSO_DB_URL is missing");
        }

        if (token == null || token.isBlank()) {
            throw new RuntimeException("TURSO_AUTH_TOKEN is missing");
        }

        Connection conn = DriverManager.getConnection(url, "", token);

        if (conn == null) {
            throw new RuntimeException("DriverManager returned a null database connection");
        }

        return conn;
    }
}