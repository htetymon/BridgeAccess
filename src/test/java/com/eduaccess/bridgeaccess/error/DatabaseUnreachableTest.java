package com.eduaccess.bridgeaccess.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Which failures count as "the database cannot be reached" (503, retry) rather than a bug (500). */
class DatabaseUnreachableTest {

	@Test
	void connectionFailuresInAnyWrapperAreUnreachable() {
		// What a cut-off TLS handshake to Supabase looks like from the JDBC template.
		SQLException handshake = new SQLException("SSL error: Remote host terminated the handshake", "08006");
		assertThat(GlobalExceptionHandler.isDatabaseUnreachable(new CannotGetJdbcConnectionException("x", handshake)))
			.isTrue();
		// From a @Transactional method: the transaction manager could not get a connection.
		assertThat(GlobalExceptionHandler.isDatabaseUnreachable(new CannotCreateTransactionException("x", handshake)))
			.isTrue();
		// The pool timed out, deep inside another exception.
		assertThat(GlobalExceptionHandler.isDatabaseUnreachable(
				new IllegalStateException(new RuntimeException(new SQLTransientConnectionException("pool timeout")))))
			.isTrue();
		// Only the SQLSTATE says so (class 08: connection exception).
		assertThat(GlobalExceptionHandler.isDatabaseUnreachable(new RuntimeException(new SQLException("x", "08001"))))
			.isTrue();
	}

	@Test
	void bugsAndRuleViolationsAreNot() {
		assertThat(GlobalExceptionHandler.isDatabaseUnreachable(new IllegalStateException("boom"))).isFalse();
		assertThat(GlobalExceptionHandler.isDatabaseUnreachable(
				new BadSqlGrammarException("q", "select", new SQLException("syntax error", "42601")))).isFalse();
		assertThat(GlobalExceptionHandler.isDatabaseUnreachable(new DataIntegrityViolationException("fk"))).isFalse();
	}

}
