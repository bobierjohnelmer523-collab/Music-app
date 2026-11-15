package com.spotifyclone.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;

@Configuration
public class DatabaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        String databaseUrl = System.getenv("DATABASE_URL");

        if (databaseUrl != null && !databaseUrl.trim().isEmpty()) {
            logger.info("Found DATABASE_URL environment variable. Configuring PostgreSQL DataSource from URI...");
            try {
                // If it already starts with jdbc:, use default parsing
                if (databaseUrl.startsWith("jdbc:")) {
                    return DataSourceBuilder.create()
                            .url(databaseUrl)
                            .driverClassName("org.postgresql.Driver")
                            .build();
                }

                // Convert postgres:// or postgresql:// to URI
                URI uri = new URI(databaseUrl);
                String userInfo = uri.getUserInfo();
                String username = null;
                String password = null;

                if (userInfo != null && userInfo.contains(":")) {
                    String[] userParts = userInfo.split(":", 2);
                    username = userParts[0];
                    password = userParts[1];
                }

                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                String path = uri.getPath(); // e.g. /neondb
                String query = uri.getQuery(); // e.g. sslmode=require

                StringBuilder jdbcUrl = new StringBuilder();
                jdbcUrl.append("jdbc:postgresql://").append(host).append(":").append(port).append(path);

                if (query != null && !query.isEmpty()) {
                    jdbcUrl.append("?").append(query);
                } else {
                    jdbcUrl.append("?sslmode=require");
                }

                logger.info("Constructed JDBC URL: jdbc:postgresql://{}:{}{}", host, port, path);

                return DataSourceBuilder.create()
                        .driverClassName("org.postgresql.Driver")
                        .url(jdbcUrl.toString())
                        .username(username)
                        .password(password)
                        .build();

            } catch (URISyntaxException e) {
                logger.error("Failed to parse DATABASE_URL as URI, falling back to standard properties", e);
            }
        }

        logger.info("Using standard DataSource configuration from application.properties");
        return properties.initializeDataSourceBuilder().build();
    }
}
