package com.tradebot;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class BudgetTargetPersistenceMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tradebot")
            .withUsername("tradebot")
            .withPassword("tradebot");

    @Test
    void migratesLegacyBudgetTargetRowsIntoV2Domain() throws Exception {
        migrateTo("35");

        UUID sessionId = UUID.randomUUID();
        UUID scanRunId = UUID.randomUUID();
        UUID recommendationId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO scan_run (id, started_at, interval_minutes, top_n, status, notes)
                    VALUES ('%s', NOW(), 20, 10, 'FINISHED', 'test')
                    """.formatted(scanRunId));
            statement.execute("""
                    INSERT INTO recommendation (id, scan_run_id, symbol, side, rationale_text, confidence_score, created_at, status)
                    VALUES ('%s', '%s', 'BTCUSDT', 'BUY', 'legacy', 88.0, NOW(), 'ACTIVE')
                    """.formatted(recommendationId, scanRunId));
            statement.execute("""
                    INSERT INTO budget_target_session (
                        id, status, completion_reason, session_budget_usdt, final_target_net_profit_usdt,
                        realized_net_pnl_usdt, active_trade_limit, trace_id, started_at, completed_at, created_at, updated_at
                    ) VALUES (
                        '%s', 'COMPLETED', 'TARGET_REACHED', 50, 10, 4.5, 3, 'trace-legacy', NOW() - INTERVAL '5 minutes',
                        NOW(), NOW() - INTERVAL '5 minutes', NOW()
                    )
                    """.formatted(sessionId));
            statement.execute("""
                    INSERT INTO budget_target_session_event (
                        id, session_id, event_type, event_status, message, reason_code, payload_json, created_at
                    ) VALUES (
                        uuid_generate_v4(), '%s', 'SESSION_COMPLETED', 'COMPLETED', 'legacy completed', 'TARGET_REACHED',
                        '{"legacy":true}'::jsonb, NOW()
                    )
                    """.formatted(sessionId));
            statement.execute("""
                    INSERT INTO live_trade_execution (
                        id, recommendation_id, trigger_mode, symbol, side, operator_id, trace_id, client_request_id,
                        dry_run, payload_snapshot_json, preflight_json, exchange_response_json, execution_state,
                        reserved_margin_usdt, realized_gross_pnl_usdt, realized_fees_usdt, realized_net_pnl_usdt,
                        close_reason, entry_client_order_id, entry_order_id, submitted_at, completed_at, created_at, updated_at,
                        budget_target_session_id
                    ) VALUES (
                        '%s', '%s', 'AUTO_SESSION', 'BTCUSDT', 'BUY', 'system', 'trace-legacy-exec', uuid_generate_v4(),
                        FALSE, '{"entryOrder":{"quantity":"0.010"}}'::jsonb,
                        '{"exchangeValidation":{"quantity":"0.010"}}'::jsonb,
                        '{"entry":{"executedQty":"0.010","resolvedFilledQuantity":"0.010","status":"FILLED"},
                          "stopLoss":{"response":{"triggerPrice":"98000.0","algoStatus":"NEW"}},
                          "takeProfit":{"response":{"triggerPrice":"101000.0","algoStatus":"NEW"}},
                          "position":{"unRealizedProfit":"0.50"}}'::jsonb,
                        'RECONCILED', 5, 1.25, 0.10, 1.15, 'TAKE_PROFIT_TRIGGERED', 'entry-1', 12345,
                        NOW() - INTERVAL '4 minutes', NOW() - INTERVAL '1 minute', NOW() - INTERVAL '4 minutes', NOW(),
                        '%s'
                    )
                    """.formatted(executionId, recommendationId, sessionId));
        }

        migrateTo(null);

        try (Connection connection = openConnection()) {
            assertEquals("STOPPED", queryText(connection,
                    "SELECT status FROM budget_target_session WHERE id = '%s'".formatted(sessionId)));
            assertEquals("TARGET_REACHED", queryText(connection,
                    "SELECT stop_reason FROM budget_target_session WHERE id = '%s'".formatted(sessionId)));
            assertEquals("TARGET_REACHED", queryText(connection,
                    "SELECT completion_reason FROM budget_target_session WHERE id = '%s'".formatted(sessionId)));
            assertEquals("0", queryText(connection,
                    "SELECT execution_failure_count::text FROM budget_target_session WHERE id = '%s'".formatted(sessionId)));
            assertTrue(queryText(connection,
                    "SELECT config_snapshot_json::text FROM budget_target_session WHERE id = '%s'".formatted(sessionId))
                    .contains("budgetAmountUsdt"));
            assertEquals("AUTO_SESSION", queryText(connection,
                    "SELECT trigger_mode FROM live_trade_execution WHERE id = '%s'".formatted(executionId)));
            assertEquals("CLOSED", queryText(connection,
                    "SELECT execution_status FROM live_trade_execution WHERE id = '%s'".formatted(executionId)));
            assertEquals("1", queryText(connection,
                    "SELECT COUNT(*)::text FROM live_trade_order WHERE execution_id = '%s' AND order_role = 'ENTRY'".formatted(executionId)));
            assertEquals("1", queryText(connection,
                    "SELECT COUNT(*)::text FROM live_trade_closure WHERE execution_id = '%s'".formatted(executionId)));
            assertEquals("2", queryText(connection,
                    "SELECT COUNT(*)::text FROM live_trade_pnl_ledger WHERE execution_id = '%s'".formatted(executionId)));
            assertEquals("1", queryText(connection,
                    "SELECT COUNT(*)::text FROM budget_target_session_event WHERE session_id = '%s' AND trace_id = 'trace-legacy'".formatted(sessionId)));
        }
    }

    @Test
    void enforcesSessionAndLedgerInvariants() throws Exception {
        migrateTo(null);

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            UUID sessionId = UUID.randomUUID();
            statement.execute("""
                    INSERT INTO budget_target_session (
                        id, status, budget_amount_usdt, target_profit_usdt, realized_net_pnl_usdt, unrealized_net_pnl_usdt,
                        max_concurrent_positions, active_positions_count, opened_positions_total, closed_positions_total,
                        trace_id, created_at, updated_at, config_snapshot_json
                    ) VALUES (
                        '%s', 'RUNNING', 50, 10, 0, 0, 3, 0, 0, 0, 'trace-active', NOW(), NOW(), '{"armed":false}'::jsonb
                    )
                    """.formatted(sessionId));

            SQLException activeSessionViolation = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO budget_target_session (
                        id, status, budget_amount_usdt, target_profit_usdt, realized_net_pnl_usdt, unrealized_net_pnl_usdt,
                        max_concurrent_positions, active_positions_count, opened_positions_total, closed_positions_total,
                        trace_id, created_at, updated_at, config_snapshot_json
                    ) VALUES (
                        '%s', 'TARGET_REACHED', 50, 10, 0, 0, 3, 0, 0, 0, 'trace-second', NOW(), NOW(), '{"armed":true}'::jsonb
                    )
                    """.formatted(UUID.randomUUID())));
            assertTrue(activeSessionViolation.getMessage().contains("uq_budget_target_session_single_active"));

            SQLException maxConcurrentViolation = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO budget_target_session (
                        id, status, budget_amount_usdt, target_profit_usdt, realized_net_pnl_usdt, unrealized_net_pnl_usdt,
                        max_concurrent_positions, active_positions_count, opened_positions_total, closed_positions_total,
                        trace_id, created_at, updated_at, config_snapshot_json
                    ) VALUES (
                        '%s', 'STOPPED', 50, 10, 0, 0, 4, 0, 0, 0, 'trace-max-bad', NOW(), NOW(), '{"armed":false}'::jsonb
                    )
                    """.formatted(UUID.randomUUID())));
            assertTrue(maxConcurrentViolation.getMessage().contains("chk_budget_target_session_max_concurrent_positions_v43"));

            UUID scanRunId = UUID.randomUUID();
            UUID recommendationId = UUID.randomUUID();
            UUID executionId = UUID.randomUUID();
            statement.execute("""
                    INSERT INTO scan_run (id, started_at, interval_minutes, top_n, status, notes)
                    VALUES ('%s', NOW(), 20, 10, 'FINISHED', 'test')
                    """.formatted(scanRunId));
            statement.execute("""
                    INSERT INTO recommendation (id, scan_run_id, symbol, side, rationale_text, confidence_score, created_at, status)
                    VALUES ('%s', '%s', 'BTCUSDT', 'BUY', 'legacy', 88.0, NOW(), 'ACTIVE')
                    """.formatted(recommendationId, scanRunId));
            statement.execute("""
                    INSERT INTO live_trade_execution (
                        id, recommendation_id, session_id, trigger_mode, symbol, side, client_request_id, dry_run,
                        execution_status, trace_id, created_at, updated_at, position_slot
                    ) VALUES (
                        '%s', '%s', '%s', 'AUTO_SESSION', 'BTCUSDT', 'BUY', uuid_generate_v4(), FALSE,
                        'ACTIVE', 'trace-exec-1', NOW(), NOW(), 1
                    )
                    """.formatted(executionId, recommendationId, sessionId));
            statement.execute("""
                    INSERT INTO live_trade_execution (
                        id, recommendation_id, session_id, trigger_mode, symbol, side, client_request_id, dry_run,
                        execution_status, trace_id, created_at, updated_at, position_slot
                    ) VALUES (
                        '%s', '%s', '%s', 'AUTO_SESSION', 'ETHUSDT', 'BUY', uuid_generate_v4(), FALSE,
                        'ACTIVE', 'trace-exec-2', NOW(), NOW(), 2
                    )
                    """.formatted(UUID.randomUUID(), recommendationId, sessionId));
            statement.execute("""
                    INSERT INTO live_trade_execution (
                        id, recommendation_id, session_id, trigger_mode, symbol, side, client_request_id, dry_run,
                        execution_status, trace_id, created_at, updated_at, position_slot
                    ) VALUES (
                        '%s', '%s', '%s', 'AUTO_SESSION', 'BNBUSDT', 'BUY', uuid_generate_v4(), FALSE,
                        'ACTIVE', 'trace-exec-3', NOW(), NOW(), 3
                    )
                    """.formatted(UUID.randomUUID(), recommendationId, sessionId));

            SQLException slotViolation = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO live_trade_execution (
                        id, recommendation_id, session_id, trigger_mode, symbol, side, client_request_id, dry_run,
                        execution_status, trace_id, created_at, updated_at, position_slot
                    ) VALUES (
                        '%s', '%s', '%s', 'AUTO_SESSION', 'SOLUSDT', 'BUY', uuid_generate_v4(), FALSE,
                        'ACTIVE', 'trace-exec-4', NOW(), NOW(), 3
                    )
                    """.formatted(UUID.randomUUID(), recommendationId, sessionId)));
            assertTrue(slotViolation.getMessage().contains("uq_live_trade_execution_active_position_slot"));

            SQLException activeCountViolation = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO budget_target_session (
                        id, status, budget_amount_usdt, target_profit_usdt, realized_net_pnl_usdt, unrealized_net_pnl_usdt,
                        max_concurrent_positions, active_positions_count, opened_positions_total, closed_positions_total,
                        trace_id, created_at, updated_at, config_snapshot_json
                    ) VALUES (
                        '%s', 'STOPPED', 50, 10, 0, 0, 4, 5, 0, 0, 'trace-bad-active', NOW(), NOW(), '{"armed":false}'::jsonb
                    )
                    """.formatted(UUID.randomUUID())));
            assertTrue(activeCountViolation.getMessage().contains("chk_budget_target_session_active_positions_count_v43"));

            statement.execute("""
                    INSERT INTO live_trade_closure (
                        id, execution_id, session_id, close_reason, trace_id, closed_at
                    ) VALUES (
                        uuid_generate_v4(), '%s', '%s', 'STOPPED', 'trace-close', NOW()
                    )
                    """.formatted(executionId, sessionId));

            SQLException closureViolation = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO live_trade_closure (
                        id, execution_id, session_id, close_reason, trace_id, closed_at
                    ) VALUES (
                        uuid_generate_v4(), '%s', '%s', 'STOPPED', 'trace-close-2', NOW()
                    )
                    """.formatted(executionId, sessionId)));
            assertTrue(closureViolation.getMessage().contains("live_trade_closure_execution_id_key"));

            statement.execute("""
                    INSERT INTO live_trade_pnl_ledger (
                        id, session_id, execution_id, event_type, amount_usdt, event_ts,
                        source_type, source_ref, trace_id
                    ) VALUES (
                        uuid_generate_v4(), '%s', '%s', 'REALIZED_GROSS_PNL', 1.25, NOW(),
                        'EXECUTION_GROSS_PNL', 'source-1', 'trace-ledger'
                    )
                    """.formatted(sessionId, executionId));

            SQLException ledgerViolation = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO live_trade_pnl_ledger (
                        id, session_id, execution_id, event_type, amount_usdt, event_ts,
                        source_type, source_ref, trace_id
                    ) VALUES (
                        uuid_generate_v4(), '%s', '%s', 'REALIZED_GROSS_PNL', 1.25, NOW(),
                        'EXECUTION_GROSS_PNL', 'source-1', 'trace-ledger'
                    )
                    """.formatted(sessionId, executionId)));
            assertTrue(ledgerViolation.getMessage().contains("uq_live_trade_pnl_ledger_source"));
        }
    }

    @Test
    void executionFailureCountDefaultsToZeroAfterMigration() throws Exception {
        migrateTo(null);

        UUID sessionId = UUID.randomUUID();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO budget_target_session (
                        id, status, budget_amount_usdt, target_profit_usdt, realized_net_pnl_usdt, unrealized_net_pnl_usdt,
                        max_concurrent_positions, active_positions_count, opened_positions_total, closed_positions_total,
                        trace_id, created_at, updated_at, config_snapshot_json
                    ) VALUES (
                        '%s', 'RUNNING', 50, 10, 0, 0, 3, 0, 0, 0, 'trace-default-count', NOW(), NOW(), '{"armed":true}'::jsonb
                    )
                    """.formatted(sessionId));
        }

        try (Connection connection = openConnection()) {
            assertEquals("0", queryText(connection,
                    "SELECT execution_failure_count::text FROM budget_target_session WHERE id = '%s'".formatted(sessionId)));
        }
    }

    @Test
    void createsExchangeSyncSnapshotTableWithExpectedColumns() throws Exception {
        migrateTo(null);

        try (Connection connection = openConnection()) {
            assertEquals("1", queryText(connection, """
                    SELECT COUNT(*)::text
                    FROM information_schema.tables
                    WHERE table_name = 'exchange_sync_snapshot'
                    """));
            assertEquals("1", queryText(connection, """
                    SELECT COUNT(*)::text
                    FROM information_schema.columns
                    WHERE table_name = 'exchange_sync_snapshot'
                      AND column_name = 'last_successful_sync_at'
                    """));
            assertEquals("1", queryText(connection, """
                    SELECT COUNT(*)::text
                    FROM information_schema.columns
                    WHERE table_name = 'exchange_sync_snapshot'
                      AND column_name = 'snapshot_json'
                    """));
        }
    }

    @Test
    void backfillsAuditEventCategorySeverityAndActorDuringV42Migration() throws Exception {
        migrateTo("41");

        UUID sessionId = UUID.randomUUID();
        UUID scanRunId = UUID.randomUUID();
        UUID recommendationId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID sessionEventId = UUID.randomUUID();
        UUID decisionAuditId = UUID.randomUUID();
        UUID executionEventId = UUID.randomUUID();

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO scan_run (id, started_at, interval_minutes, top_n, status, notes, trigger_type)
                    VALUES ('%s', NOW(), 20, 10, 'FINISHED', 'test', 'AUTO_SESSION')
                    """.formatted(scanRunId));
            statement.execute("""
                    INSERT INTO recommendation (id, scan_run_id, symbol, side, rationale_text, confidence_score, created_at, status)
                    VALUES ('%s', '%s', 'BTCUSDT', 'BUY', 'audit', 91.0, NOW(), 'ACTIVE')
                    """.formatted(recommendationId, scanRunId));
            statement.execute("""
                    INSERT INTO budget_target_session (
                        id, status, budget_amount_usdt, target_profit_usdt, realized_net_pnl_usdt, unrealized_net_pnl_usdt,
                        max_concurrent_positions, active_positions_count, opened_positions_total, closed_positions_total,
                        started_by, trace_id, created_at, updated_at, config_snapshot_json
                    ) VALUES (
                        '%s', 'RUNNING', 50, 10, 0, 0, 3, 0, 0, 0,
                        'operator-1', 'trace-audit', NOW(), NOW(), '{"budgetAmountUsdt":50}'::jsonb
                    )
                    """.formatted(sessionId));
            statement.execute("""
                    INSERT INTO live_trade_execution (
                        id, recommendation_id, session_id, trigger_mode, symbol, side, operator_id, trace_id,
                        client_request_id, dry_run, execution_status, created_at, updated_at
                    ) VALUES (
                        '%s', '%s', '%s', 'AUTO_SESSION', 'BTCUSDT', 'BUY', 'system', 'trace-execution',
                        uuid_generate_v4(), FALSE, 'ACTIVE', NOW(), NOW()
                    )
                    """.formatted(executionId, recommendationId, sessionId));
            statement.execute("""
                    INSERT INTO budget_target_session_event (
                        id, session_id, execution_id, event_type, event_status, reason_code, before_json, after_json, notes, trace_id, event_ts
                    ) VALUES (
                        '%s', '%s', '%s', 'ACTIVE_LIMIT_REACHED', 'RUNNING', 'ACTIVE_LIMIT_REACHED',
                        '{"before":true}'::jsonb, '{"after":true}'::jsonb, 'limit reached', 'trace-audit', NOW()
                    )
                    """.formatted(sessionEventId, sessionId, executionId));
            statement.execute("""
                    INSERT INTO session_symbol_decision_audit (
                        id, session_id, scan_run_id, recommendation_id, execution_id, symbol, event_type, event_ts,
                        before_json, after_json, notes, trace_id
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '%s', 'BTCUSDT', 'INTAKE_REJECTED', NOW(),
                        '{"before":true}'::jsonb, '{"after":true}'::jsonb, 'candidate rejected', 'trace-audit'
                    )
                    """.formatted(decisionAuditId, sessionId, scanRunId, recommendationId, executionId));
            statement.execute("""
                    INSERT INTO live_trade_execution_event (
                        id, execution_id, event_type, event_status, error_code, before_json, after_json, notes, trace_id, event_ts
                    ) VALUES (
                        '%s', '%s', 'SAFE_CLOSE_TIMEOUT', 'RECONCILING', 'UPSTREAM_TIMEOUT',
                        '{"before":true}'::jsonb, '{"after":true}'::jsonb, 'safe close timed out', 'trace-execution', NOW()
                    )
                    """.formatted(executionEventId, executionId));
        }

        migrateTo(null);

        try (Connection connection = openConnection()) {
            assertEquals("SESSION", queryText(connection,
                    "SELECT event_category FROM budget_target_session_event WHERE id = '%s'".formatted(sessionEventId)));
            assertEquals("WARN", queryText(connection,
                    "SELECT severity FROM budget_target_session_event WHERE id = '%s'".formatted(sessionEventId)));
            assertEquals("system", queryText(connection,
                    "SELECT actor FROM budget_target_session_event WHERE id = '%s'".formatted(sessionEventId)));

            assertEquals("TRADE", queryText(connection,
                    "SELECT event_category FROM session_symbol_decision_audit WHERE id = '%s'".formatted(decisionAuditId)));
            assertEquals("WARN", queryText(connection,
                    "SELECT severity FROM session_symbol_decision_audit WHERE id = '%s'".formatted(decisionAuditId)));
            assertEquals("system", queryText(connection,
                    "SELECT actor FROM session_symbol_decision_audit WHERE id = '%s'".formatted(decisionAuditId)));

            assertEquals("EXCHANGE", queryText(connection,
                    "SELECT event_category FROM live_trade_execution_event WHERE id = '%s'".formatted(executionEventId)));
            assertEquals("ERROR", queryText(connection,
                    "SELECT severity FROM live_trade_execution_event WHERE id = '%s'".formatted(executionEventId)));
            assertEquals("system", queryText(connection,
                    "SELECT actor FROM live_trade_execution_event WHERE id = '%s'".formatted(executionEventId)));
        }
    }

    private void migrateTo(String targetVersion) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .locations("classpath:db/migration");
        if (targetVersion != null) {
            configuration.target(targetVersion);
        }
        Flyway flyway = configuration.load();
        flyway.clean();
        flyway.migrate();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String queryText(Connection connection, String sql) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
