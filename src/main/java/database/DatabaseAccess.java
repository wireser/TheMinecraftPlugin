package database;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Central helper for all database read/write operations.
 * <p>
 * This class sits on top of {@link Database} and exposes a small set of
 * safe, reusable methods for the most common actions:
 * <ul>
 *     <li>INSERT / UPDATE / DELETE / TRUNCATE</li>
 *     <li>COUNT / COUNT(column)</li>
 *     <li>Typed single-value queries (String, int, boolean, date/time, etc.)</li>
 *     <li>Random-row helpers, and list-of-N helpers</li>
 *     <li>Prefix search (e.g. for usernames, warp names, shop names)</li>
 * </ul>
 *
 * All methods:
 * <ul>
 *     <li>Use prepared statements for values to prevent SQL injection.</li>
 *     <li>Require table/column names as separate parameters and validate them
 *         as SQL identifiers (letters, digits, underscore).</li>
 *     <li>Expect {@code whereClause} strings to use {@code ?} placeholders
 *         for user input and values supplied via {@code params}.</li>
 * </ul>
 *
 * The rule of thumb:
 * <strong>never</strong> concatenate user input directly into SQL. Pass user
 * input only via {@code params}.
 */
public final class DatabaseAccess {

    /**
     * Functional interface used to map a single {@link ResultSet} row into a value.
     *
     * @param <T> result type
     */
    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Database database;

    /**
     * Creates a new helper for the given database.
     *
     * @param database the database pool to use
     * @param logger   logger used for warnings / debug messages
     */
    public DatabaseAccess(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    // ======================================================================
    // Identifier validation
    // ======================================================================

    /**
     * Validates that the provided value is a simple SQL identifier:
     * only letters, digits and underscores are allowed.
     * <p>
     * This is used for table and column names, which cannot be bound via
     * {@link PreparedStatement} and must be part of the SQL string.
     *
     * @param identifier the identifier to validate
     * @param kind       human-readable label used in error messages
     */
    private void validateIdentifier(String identifier, String kind) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException(kind + " must not be null or empty.");
        }
        if (!identifier.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException(kind +
                    " must contain only letters, digits and underscore. Got: '" + identifier + "'");
        }
    }

    /**
     * Wraps a validated identifier in backticks to protect against reserved
     * keywords and odd characters. This expects the identifier to already
     * be validated by {@link #validateIdentifier(String, String)}.
     */
    private String quoteIdentifier(String identifier) {
        return '`' + identifier + '`';
    }

    // ======================================================================
    // Parameter binding
    // ======================================================================

    /**
     * Binds a list of parameters to a {@link PreparedStatement} in order.
     * <p>
     * Supported common types are mapped via specific setters; everything else
     * falls back to {@link PreparedStatement#setObject(int, Object)}.
     *
     * @param ps     the prepared statement
     * @param params parameters to bind (may be {@code null} or empty)
     * @throws SQLException if a JDBC error occurs
     */
    private void bindParams(PreparedStatement ps, List<?> params) throws SQLException {
        if (params == null || params.isEmpty()) {
            return;
        }

        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            int index = i + 1;

            if (value == null) {
                ps.setObject(index, null);
            } else if (value instanceof String s) {
                ps.setString(index, s);
            } else if (value instanceof Integer n) {
                ps.setInt(index, n);
            } else if (value instanceof Long n) {
                ps.setLong(index, n);
            } else if (value instanceof Boolean b) {
                ps.setBoolean(index, b);
            } else if (value instanceof Double d) {
                ps.setDouble(index, d);
            } else if (value instanceof Float f) {
                ps.setFloat(index, f);
            } else if (value instanceof java.time.LocalDate ld) {
                ps.setDate(index, Date.valueOf(ld));
            } else if (value instanceof java.time.LocalDateTime ldt) {
                ps.setTimestamp(index, Timestamp.valueOf(ldt));
            } else if (value instanceof java.time.LocalTime lt) {
                ps.setTime(index, Time.valueOf(lt));
            } else if (value instanceof java.util.Date d) {
                ps.setTimestamp(index, new Timestamp(d.getTime()));
            } else {
                ps.setObject(index, value);
            }
        }
    }

    // ======================================================================
    // Core query helpers (single / list / update)
    // ======================================================================

    /**
     * Executes an update statement (INSERT, UPDATE, DELETE, TRUNCATE, etc.).
     *
     * @param sql    the SQL string with {@code ?} placeholders
     * @param params parameter values for placeholders
     * @return number of affected rows
     * @throws SQLException if a JDBC error occurs
     */
    private int executeUpdate(String sql, List<?> params) throws SQLException {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            bindParams(ps, params);
            return ps.executeUpdate();
        }
    }

    /**
     * Executes a query expected to return at most one row.
     *
     * @param sql    the SQL string with {@code ?} placeholders
     * @param params parameter values for placeholders
     * @param mapper row mapper converting the first row into a value
     * @param <T>    result type
     * @return the mapped value or {@code null} if no row was returned
     * @throws SQLException if a JDBC error occurs
     */
    private <T> T querySingle(String sql, List<?> params, RowMapper<T> mapper) throws SQLException {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            bindParams(ps, params);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapper.map(rs);
                }
                return null;
            }
        }
    }

    /**
     * Executes a query and returns a list of mapped results.
     *
     * @param sql    the SQL string with {@code ?} placeholders
     * @param params parameter values for placeholders
     * @param limit  maximum number of rows to read; {@code <= 0} means "no limit"
     * @param mapper row mapper converting each row into a value
     * @param <T>    result type
     * @return list of mapped values (never {@code null})
     * @throws SQLException if a JDBC error occurs
     */
    private <T> List<T> queryList(String sql, List<?> params, int limit, RowMapper<T> mapper) throws SQLException {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            bindParams(ps, params);

            try (ResultSet rs = ps.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapper.map(rs));
                    if (limit > 0 && result.size() >= limit) {
                        break;
                    }
                }
                return result;
            }
        }
    }

    // ======================================================================
    // CRUD: insert / update / delete / truncate
    // ======================================================================

    /**
     * Executes an INSERT into the given table.
     *
     * @param table   table name (only letters, digits, underscore)
     * @param columns column names to set
     * @param values  values to insert (must match {@code columns} size)
     * @return number of inserted rows (usually {@code 1})
     * @throws SQLException if a JDBC error occurs
     */
    public int insert(String table, List<String> columns, List<?> values) throws SQLException {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(values, "values");
        if (columns.size() != values.size()) {
            throw new IllegalArgumentException("columns and values must have the same size.");
        }

        validateIdentifier(table, "table");
        for (String col : columns) {
            validateIdentifier(col, "column");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(quoteIdentifier(table)).append(" (");

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(quoteIdentifier(columns.get(i)));
        }

        sql.append(") VALUES (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(")");

        return executeUpdate(sql.toString(), values);
    }

    /**
     * Executes an UPDATE on the given table.
     *
     * @param table        table name
     * @param setColumns   columns to update
     * @param setValues    new values (must match {@code setColumns} size)
     * @param whereClause  optional WHERE clause (without the {@code WHERE} keyword),
     *                     should use {@code ?} placeholders for user input
     * @param whereParams  parameters for the WHERE placeholders (may be {@code null})
     * @return number of affected rows
     * @throws SQLException if a JDBC error occurs
     */
    public int update(String table,
                      List<String> setColumns,
                      List<?> setValues,
                      String whereClause,
                      List<?> whereParams) throws SQLException {

        Objects.requireNonNull(setColumns, "setColumns");
        Objects.requireNonNull(setValues, "setValues");
        if (setColumns.size() != setValues.size()) {
            throw new IllegalArgumentException("setColumns and setValues must have the same size.");
        }

        validateIdentifier(table, "table");
        for (String col : setColumns) {
            validateIdentifier(col, "column");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(quoteIdentifier(table)).append(" SET ");

        for (int i = 0; i < setColumns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(quoteIdentifier(setColumns.get(i))).append(" = ?");
        }

        if (whereClause != null && !whereClause.isBlank()) {
            sql.append(" WHERE ").append(whereClause);
        }

        List<Object> params = new ArrayList<>(setValues.size() + (whereParams != null ? whereParams.size() : 0));
        params.addAll(setValues);
        if (whereParams != null) {
            params.addAll(whereParams);
        }

        return executeUpdate(sql.toString(), params);
    }

    /**
     * Executes a DELETE on the given table.
     *
     * @param table       table name
     * @param whereClause WHERE clause (without {@code WHERE}) or {@code null} to delete all rows
     * @param params      parameters for the WHERE placeholders
     * @return number of deleted rows
     * @throws SQLException if a JDBC error occurs
     */
    public int delete(String table, String whereClause, List<?> params) throws SQLException {
        validateIdentifier(table, "table");

        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM ").append(quoteIdentifier(table));

        if (whereClause != null && !whereClause.isBlank()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return executeUpdate(sql.toString(), params);
    }

    /**
     * Truncates the given table.
     *
     * @param table table name
     * @throws SQLException if a JDBC error occurs
     */
    public void truncate(String table) throws SQLException {
        validateIdentifier(table, "table");
        String sql = "TRUNCATE TABLE " + quoteIdentifier(table);
        executeUpdate(sql, Collections.emptyList());
    }

    // ======================================================================
    // Count utilities
    // ======================================================================

    /**
     * Counts rows in the given table that match the WHERE clause.
     *
     * @param table       table name
     * @param whereClause WHERE clause without {@code WHERE}, may be {@code null}
     * @param params      parameters for the WHERE placeholders
     * @return number of matching rows, or {@code 0} if none
     * @throws SQLException if a JDBC error occurs
     */
    public int count(String table, String whereClause, List<?> params) throws SQLException {
        validateIdentifier(table, "table");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM ").append(quoteIdentifier(table));

        if (whereClause != null && !whereClause.isBlank()) {
            sql.append(" WHERE ").append(whereClause);
        }

        Integer result = querySingle(sql.toString(), params, rs -> rs.getInt(1));
        return result != null ? result : 0;
    }

    /**
     * Counts non-null values of a specific column, optionally with a WHERE clause.
     *
     * @param table       table name
     * @param column      column name
     * @param whereClause WHERE clause without {@code WHERE}, may be {@code null}
     * @param params      parameters for the WHERE placeholders
     * @return count of non-null values
     * @throws SQLException if a JDBC error occurs
     */
    public int countAll(String table, String column, String whereClause, List<?> params) throws SQLException {
        validateIdentifier(table, "table");
        validateIdentifier(column, "column");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(").append(quoteIdentifier(column)).append(") FROM ")
                .append(quoteIdentifier(table));

        if (whereClause != null && !whereClause.isBlank()) {
            sql.append(" WHERE ").append(whereClause);
        }

        Integer result = querySingle(sql.toString(), params, rs -> rs.getInt(1));
        return result != null ? result : 0;
    }

    // ======================================================================
    // Generic single-value & list helpers (internal)
    // ======================================================================

    private <T> T getSingleValue(String table,
                                 String column,
                                 String whereClause,
                                 List<?> params,
                                 RowMapper<T> mapper) throws SQLException {

        validateIdentifier(table, "table");
        validateIdentifier(column, "column");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(quoteIdentifier(column))
                .append(" FROM ").append(quoteIdentifier(table));

        if (whereClause != null && !whereClause.isBlank()) {
            sql.append(" WHERE ").append(whereClause);
        }

        sql.append(" LIMIT 1");

        return querySingle(sql.toString(), params, mapper);
    }

    private <T> T getRandomValue(String table,
                                 String column,
                                 String whereClause,
                                 List<?> params,
                                 RowMapper<T> mapper) throws SQLException {

        validateIdentifier(table, "table");
        validateIdentifier(column, "column");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(quoteIdentifier(column))
                .append(" FROM ").append(quoteIdentifier(table));

        if (whereClause != null && !whereClause.isBlank()) {
            sql.append(" WHERE ").append(whereClause);
        }

        sql.append(" ORDER BY RAND() LIMIT 1");

        return querySingle(sql.toString(), params, mapper);
    }

    private <T> List<T> getValueList(String table,
                                     String column,
                                     String whereClause,
                                     List<?> params,
                                     int limit,
                                     RowMapper<T> mapper) throws SQLException {

        validateIdentifier(table, "table");
        validateIdentifier(column, "column");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(quoteIdentifier(column))
                .append(" FROM ").append(quoteIdentifier(table));

        if (whereClause != null && !whereClause.isBlank()) {
            sql.append(" WHERE ").append(whereClause);
        }

        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }

        return queryList(sql.toString(), params, limit, mapper);
    }

    // ======================================================================
    // Typed getters: String / boolean / number / time types
    // ======================================================================

    // --- String ---

    public String getString(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getSingleValue(table, column, whereClause, params, rs -> rs.getString(1));
    }

    public String getRandomString(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getRandomValue(table, column, whereClause, params, rs -> rs.getString(1));
    }

    public List<String> getStringList(String table, String column, String whereClause, List<?> params, int limit) throws SQLException {
        return getValueList(table, column, whereClause, params, limit, rs -> rs.getString(1));
    }

    // --- Boolean ---

    public Boolean getBoolean(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getSingleValue(table, column, whereClause, params, rs -> {
            boolean v = rs.getBoolean(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    public Boolean getRandomBoolean(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getRandomValue(table, column, whereClause, params, rs -> {
            boolean v = rs.getBoolean(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    public List<Boolean> getBooleanList(String table, String column, String whereClause, List<?> params, int limit) throws SQLException {
        return getValueList(table, column, whereClause, params, limit, rs -> {
            boolean v = rs.getBoolean(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    // --- Integer ---

    public Integer getInt(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getSingleValue(table, column, whereClause, params, rs -> {
            int v = rs.getInt(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    public Integer getRandomInt(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getRandomValue(table, column, whereClause, params, rs -> {
            int v = rs.getInt(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    public List<Integer> getIntList(String table, String column, String whereClause, List<?> params, int limit) throws SQLException {
        return getValueList(table, column, whereClause, params, limit, rs -> {
            int v = rs.getInt(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    // --- Long ---

    public Long getLong(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getSingleValue(table, column, whereClause, params, rs -> {
            long v = rs.getLong(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    public Long getRandomLong(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getRandomValue(table, column, whereClause, params, rs -> {
            long v = rs.getLong(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    public List<Long> getLongList(String table, String column, String whereClause, List<?> params, int limit) throws SQLException {
        return getValueList(table, column, whereClause, params, limit, rs -> {
            long v = rs.getLong(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    // --- Float ---

    public Float getFloat(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getSingleValue(table, column, whereClause, params, rs -> {
            float v = rs.getFloat(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    public Float getRandomFloat(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getRandomValue(table, column, whereClause, params, rs -> {
            float v = rs.getFloat(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    public List<Float> getFloatList(String table, String column, String whereClause, List<?> params, int limit) throws SQLException {
        return getValueList(table, column, whereClause, params, limit, rs -> {
            float v = rs.getFloat(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    // --- Double ---

    public Double getDouble(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getSingleValue(table, column, whereClause, params, rs -> {
            double v = rs.getDouble(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    public Double getRandomDouble(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getRandomValue(table, column, whereClause, params, rs -> {
            double v = rs.getDouble(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    public List<Double> getDoubleList(String table, String column, String whereClause, List<?> params, int limit) throws SQLException {
        return getValueList(table, column, whereClause, params, limit, rs -> {
            double v = rs.getDouble(1);
            if (rs.wasNull()) return null;
            return v;
        });
    }

    // --- Time / Date / DateTime / Timestamp using java.time ---

    public LocalDate getDate(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getSingleValue(table, column, whereClause, params, rs -> {
            Date d = rs.getDate(1);
            return d != null ? d.toLocalDate() : null;
        });
    }

    public LocalDate getRandomDate(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getRandomValue(table, column, whereClause, params, rs -> {
            Date d = rs.getDate(1);
            return d != null ? d.toLocalDate() : null;
        });
    }

    public List<LocalDate> getDateList(String table, String column, String whereClause, List<?> params, int limit) throws SQLException {
        return getValueList(table, column, whereClause, params, limit, rs -> {
            Date d = rs.getDate(1);
            return d != null ? d.toLocalDate() : null;
        });
    }

    public LocalTime getTime(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getSingleValue(table, column, whereClause, params, rs -> {
            Time t = rs.getTime(1);
            return t != null ? t.toLocalTime() : null;
        });
    }

    public LocalTime getRandomTime(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getRandomValue(table, column, whereClause, params, rs -> {
            Time t = rs.getTime(1);
            return t != null ? t.toLocalTime() : null;
        });
    }

    public List<LocalTime> getTimeList(String table, String column, String whereClause, List<?> params, int limit) throws SQLException {
        return getValueList(table, column, whereClause, params, limit, rs -> {
            Time t = rs.getTime(1);
            return t != null ? t.toLocalTime() : null;
        });
    }

    public LocalDateTime getDateTime(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getSingleValue(table, column, whereClause, params, rs -> {
            Timestamp ts = rs.getTimestamp(1);
            return ts != null ? ts.toLocalDateTime() : null;
        });
    }

    public LocalDateTime getRandomDateTime(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getRandomValue(table, column, whereClause, params, rs -> {
            Timestamp ts = rs.getTimestamp(1);
            return ts != null ? ts.toLocalDateTime() : null;
        });
    }

    public List<LocalDateTime> getDateTimeList(String table, String column, String whereClause, List<?> params, int limit) throws SQLException {
        return getValueList(table, column, whereClause, params, limit, rs -> {
            Timestamp ts = rs.getTimestamp(1);
            return ts != null ? ts.toLocalDateTime() : null;
        });
    }

    public Instant getTimestamp(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getSingleValue(table, column, whereClause, params, rs -> {
            Timestamp ts = rs.getTimestamp(1);
            return ts != null ? ts.toInstant() : null;
        });
    }

    public Instant getRandomTimestamp(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getRandomValue(table, column, whereClause, params, rs -> {
            Timestamp ts = rs.getTimestamp(1);
            return ts != null ? ts.toInstant() : null;
        });
    }

    public List<Instant> getTimestampList(String table, String column, String whereClause, List<?> params, int limit) throws SQLException {
        return getValueList(table, column, whereClause, params, limit, rs -> {
            Timestamp ts = rs.getTimestamp(1);
            return ts != null ? ts.toInstant() : null;
        });
    }

    // ======================================================================
    // Prefix search / "AJAX-like" helper
    // ======================================================================

    /**
     * Returns a list of values (typically usernames, warp names, etc.) from
     * a given table and column whose value starts with the specified prefix.
     * <p>
     * Example usage:
     * <pre>
     *   List&lt;String&gt; names = sql.searchPrefix("players", "username", "the", 20);
     * </pre>
     *
     * @param table   table name
     * @param column  column name to search
     * @param prefix  prefix to match (case-sensitivity depends on DB collation)
     * @param limit   maximum number of results to return (e.g. 20)
     * @return list of matching values, ordered by the column ascending
     * @throws SQLException if a JDBC error occurs
     */
    public List<String> searchPrefix(String table,
                                     String column,
                                     String prefix,
                                     int limit) throws SQLException {

        validateIdentifier(table, "table");
        validateIdentifier(column, "column");

        if (prefix == null) {
            prefix = "";
        }

        if (limit <= 0) {
            limit = 20;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(quoteIdentifier(column))
                .append(" FROM ").append(quoteIdentifier(table))
                .append(" WHERE ").append(quoteIdentifier(column)).append(" LIKE ?")
                .append(" ORDER BY ").append(quoteIdentifier(column)).append(" ASC")
                .append(" LIMIT ?");

        List<Object> params = new ArrayList<>(2);
        params.add(prefix + "%");
        params.add(limit);

        return queryList(sql.toString(), params, limit, rs -> rs.getString(1));
    }

    // ======================================================================
    // Convenience overloads with varargs (optional, similar to your style)
    // ======================================================================

    /**
     * Convenience wrapper matching your existing style:
     * {@code gets(table, column, condition, params...)}.
     *
     * @param table      table name
     * @param column     column name
     * @param whereClause WHERE clause without {@code WHERE}
     * @param params     parameters for placeholders
     * @return first matching string or {@code null}
     * @throws SQLException if a JDBC error occurs
     */
    public String gets(String table, String column, String whereClause, Object... params) throws SQLException {
        List<?> list = (params == null || params.length == 0)
                ? Collections.emptyList()
                : List.of(params);
        return getString(table, column, whereClause, list);
    }

    /**
     * Convenience wrapper matching your previous {@code getList} idea.
     */
    public List<String> getList(String table, String column, String whereClause, List<?> params) throws SQLException {
        return getStringList(table, column, whereClause, params, 0);
    }
    
    /**
     * Generic UPSERT helper:
     * 1) Tries to UPDATE the row identified by {@code keyColumns} / {@code keyValues}.
     * 2) If no row was updated, INSERTs a new row with both key + update columns.
     *
     * This is perfect for tables where you have a natural key like
     * (player_id, `key`) or (player_id, entity_id).
     *
     * @param table         table name
     * @param keyColumns    columns that uniquely identify the row (WHERE)
     * @param keyValues     values for keyColumns
     * @param updateColumns columns to set/update
     * @param updateValues  values for updateColumns
     * @return number of affected rows (1 on success)
     */
    public int upsert(String table,
                      List<String> keyColumns,
                      List<?> keyValues,
                      List<String> updateColumns,
                      List<?> updateValues) throws SQLException {

        Objects.requireNonNull(keyColumns, "keyColumns");
        Objects.requireNonNull(keyValues, "keyValues");
        Objects.requireNonNull(updateColumns, "updateColumns");
        Objects.requireNonNull(updateValues, "updateValues");

        if (keyColumns.size() != keyValues.size()) {
            throw new IllegalArgumentException("keyColumns and keyValues must have the same size.");
        }
        if (updateColumns.size() != updateValues.size()) {
            throw new IllegalArgumentException("updateColumns and updateValues must have the same size.");
        }

        validateIdentifier(table, "table");
        for (String col : keyColumns) {
            validateIdentifier(col, "key column");
        }
        for (String col : updateColumns) {
            validateIdentifier(col, "update column");
        }

        // 1) UPDATE
        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(quoteIdentifier(table)).append(" SET ");

        for (int i = 0; i < updateColumns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(quoteIdentifier(updateColumns.get(i))).append(" = ?");
        }

        sql.append(" WHERE ");
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) sql.append(" AND ");
            sql.append(quoteIdentifier(keyColumns.get(i))).append(" = ?");
        }

        List<Object> params = new ArrayList<>(updateValues.size() + keyValues.size());
        params.addAll(updateValues);
        params.addAll(keyValues);

        int updated = executeUpdate(sql.toString(), params);
        if (updated > 0) {
            return updated;
        }

        // 2) INSERT (no row existed)
        List<String> allColumns = new ArrayList<>(keyColumns.size() + updateColumns.size());
        allColumns.addAll(keyColumns);
        allColumns.addAll(updateColumns);

        List<Object> allValues = new ArrayList<>(keyValues.size() + updateValues.size());
        allValues.addAll(keyValues);
        allValues.addAll(updateValues);

        return insert(table, allColumns, allValues);
    }
    
    /**
     * Increments a numeric column by {@code delta} for a row identified
     * by {@code keyColumns} / {@code keyValues}. If no row exists, inserts
     * one with the column initialized to {@code delta}.
     *
     * Works great for counters (kill counts, stats, balances, etc.).
     *
     * @param table       table name
     * @param keyColumns  key columns (used in WHERE and INSERT)
     * @param keyValues   key values
     * @param valueColumn numeric column to increment
     * @param delta       increment amount (may be negative)
     * @return number of affected rows (1 on success, 0 if delta == 0)
     */
    public int incrementOrInsert(String table,
                                 List<String> keyColumns,
                                 List<?> keyValues,
                                 String valueColumn,
                                 Number delta) throws SQLException {

        if (delta == null || delta.doubleValue() == 0.0d) {
            return 0;
        }

        Objects.requireNonNull(keyColumns, "keyColumns");
        Objects.requireNonNull(keyValues, "keyValues");

        if (keyColumns.size() != keyValues.size()) {
            throw new IllegalArgumentException("keyColumns and keyValues must have the same size.");
        }

        validateIdentifier(table, "table");
        validateIdentifier(valueColumn, "value column");
        for (String col : keyColumns) {
            validateIdentifier(col, "key column");
        }

        // 1) Try UPDATE value = value + ?
        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(quoteIdentifier(table))
           .append(" SET ").append(quoteIdentifier(valueColumn))
           .append(" = ").append(quoteIdentifier(valueColumn)).append(" + ?")
           .append(" WHERE ");

        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) sql.append(" AND ");
            sql.append(quoteIdentifier(keyColumns.get(i))).append(" = ?");
        }

        List<Object> params = new ArrayList<>(1 + keyValues.size());
        params.add(delta);
        params.addAll(keyValues);

        int updated = executeUpdate(sql.toString(), params);
        if (updated > 0) {
            return updated;
        }

        // 2) No row -> INSERT new one with value = delta
        List<String> allColumns = new ArrayList<>(keyColumns.size() + 1);
        allColumns.addAll(keyColumns);
        allColumns.add(valueColumn);

        List<Object> allValues = new ArrayList<>(keyValues.size() + 1);
        allValues.addAll(keyValues);
        allValues.add(delta);

        return insert(table, allColumns, allValues);
    }

    
}