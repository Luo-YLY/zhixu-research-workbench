package local.research.workbench.shared;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public final class SqlSupport {
    private SqlSupport() {}
    public static String id() { return UUID.randomUUID().toString(); }
    public static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    public static Instant time(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
