package me.kzheart.klib.data.jdbc;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.Arrays;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

class KotlinJdbcAbiTest {
    @Test
    void staticFactoriesAndCheckedQueryContractsRemainJavaFriendly() throws Exception {
        assertTrue(Modifier.isStatic(JdbcExecutor.class.getMethod("open", DataSource.class,
                int.class, int.class, SqlMigrationEngine.class, int.class).getModifiers()));
        assertTrue(Modifier.isStatic(SqlTransactionOptions.class.getMethod("defaults").getModifiers()));
        assertEquals(Arrays.asList(SQLException.class), Arrays.asList(SqlClient.class.getMethod(
                "queryOne", String.class, SqlParameters.class, RowMapper.class).getExceptionTypes()));
        assertEquals(Arrays.asList(Exception.class), Arrays.asList(SqlMigrationEngine.class.getMethod(
                "currentVersion", DataSource.class).getExceptionTypes()));
        SqlParameters binding = statement -> { throw new SQLException("checked"); };
        RowMapper<String> mapper = row -> "java";
        assertNotNull(binding);
        assertNotNull(mapper);
        assertNotNull(SqlTransactionOptions.defaults().withReadOnly(true));
    }
}
