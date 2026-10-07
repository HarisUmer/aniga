package com.aniga.backend.db;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Applies the Firebase-era and people tables on startup. Safe to run on every boot. */
@Component
public class FirebaseSchema {

    private static final String[] SCRIPTS = {"db/010_firebase_auth.sql", "db/011_people.sql"};

    public FirebaseSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            for (String script : SCRIPTS) {
                apply(statement, script);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not prepare the Postgres tables", e);
        }
    }

    private static void apply(Statement statement, String script) {
        try (var in = new ClassPathResource(script).getInputStream()) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String command : SqlScript.statements(sql)) {
                statement.execute(command);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not apply " + script, e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + script, e);
        }
    }
}
