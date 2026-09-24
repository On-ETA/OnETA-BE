package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.SQLException;

public class V18__unify_email_verification_verified extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            boolean legacy = false;
            boolean current = false;
            try (var columns = statement.executeQuery("SHOW COLUMNS FROM email_verifications")) {
                while (columns.next()) {
                    legacy |= "is_verified".equals(columns.getString("Field"));
                    current |= "verified".equals(columns.getString("Field"));
                }
            }
            if (!legacy && !current) {
                throw new SQLException("email_verifications has no verification status column");
            }
            if (!legacy) {
                return;
            }
            if (!current) {
                statement.execute("ALTER TABLE email_verifications RENAME COLUMN is_verified TO verified");
                return;
            }
            // Never guess which status is authoritative: doing so can grant verification
            // or lose a previously verified state. Resolve conflicts before retrying.
            try (var conflicts = statement.executeQuery(
                    "SELECT COUNT(*) FROM email_verifications WHERE NOT (is_verified <=> verified)")) {
                conflicts.next();
                if (conflicts.getLong(1) != 0) {
                    throw new SQLException("Conflicting email verification states; reconcile is_verified and verified before retrying V18");
                }
            }
            statement.execute("ALTER TABLE email_verifications DROP COLUMN is_verified");
        }
    }
}
