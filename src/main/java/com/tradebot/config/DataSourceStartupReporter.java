package com.tradebot.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSourceStartupReporter implements ApplicationRunner {

    private static final Pattern JDBC_URL_PATTERN = Pattern.compile(
            "^jdbc:postgresql://(?<host>[^/:?]+)(:(?<port>\\d+))?/(?<database>[^?]+).*$");

    private final Environment environment;
    private final ObjectProvider<DataSource> dataSourceProvider;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        String jdbcUrl = environment.getProperty("spring.datasource.url", "");
        String username = environment.getProperty("spring.datasource.username", "");
        boolean flywayEnabled = environment.getProperty("spring.flyway.enabled", Boolean.class, true);
        boolean openInView = environment.getProperty("spring.jpa.open-in-view", Boolean.class, true);
        JdbcLocation jdbcLocation = JdbcLocation.parse(jdbcUrl);

        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            log.info(
                    "Datasource config: host={} port={} database={} user={} pool={} maxPoolSize={} minIdle={} connectionTimeoutMs={} validationTimeoutMs={} initializationFailTimeoutMs={} flywayEnabled={} openInView={} activeConnections={} idleConnections={} threadsAwaiting={}",
                    jdbcLocation.host(),
                    jdbcLocation.port(),
                    jdbcLocation.database(),
                    username,
                    hikari.getPoolName(),
                    hikari.getMaximumPoolSize(),
                    hikari.getMinimumIdle(),
                    hikari.getConnectionTimeout(),
                    hikari.getValidationTimeout(),
                    hikari.getInitializationFailTimeout(),
                    flywayEnabled,
                    openInView,
                    pool != null ? pool.getActiveConnections() : -1,
                    pool != null ? pool.getIdleConnections() : -1,
                    pool != null ? pool.getThreadsAwaitingConnection() : -1);
        } else {
            log.info(
                    "Datasource config: host={} port={} database={} user={} flywayEnabled={} openInView={}",
                    jdbcLocation.host(),
                    jdbcLocation.port(),
                    jdbcLocation.database(),
                    username,
                    flywayEnabled,
                    openInView);
        }

        if (dataSource == null) {
            return;
        }

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("select current_database(), current_user")) {
            if (resultSet.next()) {
                log.info("Datasource validation succeeded: currentDatabase={} currentUser={}",
                        resultSet.getString(1),
                        resultSet.getString(2));
            }
        }
    }

    private record JdbcLocation(String host, int port, String database) {
        private static JdbcLocation parse(String jdbcUrl) {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                return new JdbcLocation("n/a", -1, "n/a");
            }
            Matcher matcher = JDBC_URL_PATTERN.matcher(jdbcUrl.trim());
            if (!matcher.matches()) {
                return new JdbcLocation("unknown", -1, "unknown");
            }
            String portValue = matcher.group("port");
            return new JdbcLocation(
                    matcher.group("host"),
                    portValue == null || portValue.isBlank() ? 5432 : Integer.parseInt(portValue),
                    matcher.group("database"));
        }
    }
}
