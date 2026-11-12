package com.gkk.itip.api.analysis;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class FolderNameMigrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Test void duplicateLegacyNamesStopMigrationWithoutChangingFoldersAndCanBeRenamedBeforeRetry() throws Exception {
        verifyLegacyNames("duplicate_names", "AI 공부", "ai 공부", "Duplicate folder names");
    }

    @Test void tooLongLegacyNameStopsMigrationWithoutTruncationAndCanBeRenamedBeforeRetry() throws Exception {
        verifyLegacyNames("long_names", "가".repeat(21), "다른 이름", "exceed 20 code points");
    }

    private void verifyLegacyNames(String schema, String first, String second, String message) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create extension if not exists vector with schema public");
        }
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema);
        config.target("7").load().migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var insert = connection.prepareStatement("insert into " + schema + ".folders(id,owner_id,name,created_at) values (gen_random_uuid(),'demo-user',?,now())")) {
                for (String name : new String[]{first, second}) {
                    insert.setString(1, name);
                    insert.executeUpdate();
                }
            }
            var latest = config.target("latest").load();
            assertThatThrownBy(latest::migrate).hasStackTraceContaining(message);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("select name from " + schema + ".folders order by name")) {
                var names = new java.util.ArrayList<String>();
                while (rows.next()) names.add(rows.getString(1));
                assertThat(names).containsExactlyInAnyOrder(first, second);
            }
            try (var rename = connection.prepareStatement("update " + schema + ".folders set name='수동 변경' where name=?")) {
                rename.setString(1, first);
                rename.executeUpdate();
            }
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        }
    }
}
