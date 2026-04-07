package com.backend.letterlink;

import java.sql.Connection;
import java.sql.DriverManager;

public class Database {

    public static Connection getConnection() throws Exception {
        String url = System.getenv("TURSO_DB_URL");
        String token = System.getenv("TURSO_AUTH_TOKEN");

        return DriverManager.getConnection(url, "", token);
    }
}