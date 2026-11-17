package com.clydeexchange.server.store;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * A real, empty H2 database that lives in memory, with the real tables created by the real Flyway
 * script. Tests keep hold of one of these while they throw an exchange away and build a new one,
 * which is exactly what a server restart does.
 */
public final class TestDatabase {

    public final JdbcTemplate jdbc;
    public final JdbcExchangeStore store;
    public final JdbcNewsStore newsStore;

    public TestDatabase() {
        this(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"));
    }

    /** The same, in any empty database: PostgresTest passes a real PostgreSQL. */
    public TestDatabase(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcExchangeStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        newsStore = new JdbcNewsStore(jdbc);
    }
}
