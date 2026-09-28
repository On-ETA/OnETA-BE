package com.OnETA.migration;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationVersionUniquenessTest {
    @Test
    void sqlAndJavaMigrationsHaveDistinctVersionsOnRuntimeClasspath() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        var namePattern = Pattern.compile("V([0-9][0-9._]*)__.+\\.(sql|class)");
        Map<MigrationVersion, List<String>> migrations = new TreeMap<>();
        for (var resource : resolver.getResources("classpath*:db/migration/V*")) {
            var name = resource.getFilename();
            if (name == null || name.contains("$")) continue;
            var match = namePattern.matcher(name);
            if (!match.matches()) continue;
            migrations.computeIfAbsent(MigrationVersion.fromVersion(match.group(1)), key -> new ArrayList<>())
                    .add(resource.getURL().toString());
        }
        assertThat(migrations).isNotEmpty();
        var duplicates = migrations.entrySet().stream().filter(entry -> entry.getValue().size() > 1).toList();
        assertThat(duplicates).as("SQL and Java migration versions must be globally unique").isEmpty();
        assertThat(migrations.values().stream().flatMap(List::stream))
                .anyMatch(path -> path.endsWith("V18__unify_email_verification_verified.class"))
                .anyMatch(path -> path.endsWith("V19__make_transit_notifications_one_time.sql"))
                .anyMatch(path -> path.endsWith("V20__archive_duplicate_transit_notifications.sql"));
    }
}
