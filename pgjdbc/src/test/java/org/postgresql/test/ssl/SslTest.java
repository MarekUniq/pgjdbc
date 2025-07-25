/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.Version;
import org.postgresql.jdbc.GSSEncMode;
import org.postgresql.jdbc.SslMode;
import org.postgresql.jdbc.SslNegotiation;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.net.SocketException;
import java.security.cert.CertPathValidatorException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.net.ssl.SSLHandshakeException;

// If you want to execute a specific test iteration, comment-out @MethodSource("data") and
// update dataSubset method to return only the iterations you need.
@ParameterizedClass
@MethodSource("data")
@MethodSource("dataSubset")
@Execution(ExecutionMode.CONCURRENT)
public class SslTest {
  enum Hostname {
    GOOD("localhost"),
    BAD("127.0.0.1"),
    ;

    final String value;

    Hostname(String value) {
      this.value = value;
    }
  }

  enum TestDatabase {
    hostdb,
    hostnossldb,
    hostssldb,
    hostsslcertdb,
    certdb,
    ;

    public static final TestDatabase[] VALUES = values();

    public boolean requiresClientCert() {
      return this == certdb || this == hostsslcertdb;
    }

    public boolean requiresSsl() {
      return this == certdb || this == hostssldb || this == hostsslcertdb;
    }

    public boolean rejectsSsl() {
      return this == hostnossldb;
    }
  }

  enum ClientCertificate {
    EMPTY(""),
    GOOD("goodclient"),
    BAD("badclient"),
    ;

    public static final ClientCertificate[] VALUES = values();
    public final String fileName;

    ClientCertificate(String fileName) {
      this.fileName = fileName;
    }
  }

  enum ClientRootCertificate {
    EMPTY(""),
    GOOD("goodroot"),
    BAD("badroot"),
    ;

    public static final ClientRootCertificate[] VALUES = values();
    public final String fileName;

    ClientRootCertificate(String fileName) {
      this.fileName = fileName;
    }
  }

  enum ChannelBinding {
    DISABLE("disable"),
    PREFER("prefer"),
    REQUIRE("require"),
    ;

    public static final ChannelBinding[] VALUES = values();
    public final String value;

    ChannelBinding(String value) {
      this.value = value;
    }
  }

  enum Role {
    CLIENT_CERT_ROLE("md5", TestUtil.getUser() /* it has to match CN in the certificate */),
    MD5_ROLE("md5", "ssl_test_md5"),
    SCRAM_ROLE("scram-sha-256", "ssl_test_scram");

    public static final Role[] VALUES = values();
    public final String passwordEncryption;
    public final String username;

    Role(String passwordEncryption, String username) {
      this.passwordEncryption = passwordEncryption;
      this.username = username;
    }

    String getPassword() {
      return "ssl_test_pass_" + name().toLowerCase(Locale.ROOT);
    }
  }

  private final Hostname host;
  private final TestDatabase db;
  private final SslMode sslmode;
  private final ChannelBinding channelBinding;
  private final SslNegotiation sslNegotiation;
  private final ClientCertificate clientCertificate;
  private final ClientRootCertificate clientRootCertificate;
  private final GSSEncMode gssEncMode;
  private final Role clientRole;

  SslTest(Hostname host, TestDatabase db, SslMode sslmode, ChannelBinding channelBinding, SslNegotiation sslNegotiation,
      ClientCertificate clientCertificate, ClientRootCertificate clientRootCertificate,
      GSSEncMode gssEncMode, Role clientRole) {
    this.host = host;
    this.db = db;
    this.sslmode = sslmode;
    this.channelBinding = channelBinding;
    this.sslNegotiation = sslNegotiation;
    this.clientCertificate = clientCertificate;
    this.clientRootCertificate = clientRootCertificate;
    this.gssEncMode = gssEncMode;
    this.clientRole = clientRole;
  }

  public static List<Object[]> dataSubset() throws SQLException {
    return data().subList(1, 1);
  }

  public static List<Object[]> data() throws SQLException {
    List<Object[]> tests = new ArrayList<>();

    Version serverVersion;
    try (Connection con = TestUtil.openDB()) {
      serverVersion = ServerVersion.from(con.getMetaData().getDatabaseProductVersion());
    }

    for (SslNegotiation sslNegotiation :  SslNegotiation.values()) {
      if (sslNegotiation == SslNegotiation.DIRECT) {
        if (serverVersion.getVersionNum() < ServerVersion.v17.getVersionNum()) {
          continue; // ignore direct connection unless we have version 17
        }
      }
      // iterate over all possible combinations of parameters
      for (SslMode sslMode : SslMode.VALUES) {
        if (sslMode == SslMode.DISABLE && sslNegotiation == SslNegotiation.DIRECT) {
          // no need to test as this is the same as DISABLE and POSTGRESQL
          continue;
        }
        for (ChannelBinding channelBinding : ChannelBinding.VALUES) {
          if (serverVersion.getVersionNum() < ServerVersion.v11.getVersionNum()
              && channelBinding != ChannelBinding.DISABLE) {
            // PostgreSQL supports channel binding in 11+
            continue;
          }
          for (Hostname hostname : Hostname.values()) {
            for (TestDatabase database : TestDatabase.VALUES) {
              if (database.rejectsSsl() && sslNegotiation == SslNegotiation.DIRECT) {
                // The database would reject TLS anyway, so there's no need to test "direct" TLS
                // connection
                continue;
              }
              for (ClientCertificate clientCertificate : ClientCertificate.VALUES) {
                for (ClientRootCertificate rootCertificate : ClientRootCertificate.VALUES) {
                  if ((sslMode == SslMode.DISABLE
                      || database.rejectsSsl())
                      && (clientCertificate != ClientCertificate.EMPTY
                      || rootCertificate != ClientRootCertificate.EMPTY)) {
                    // When SSL is disabled, it does not make sense to verify "bad certificates"
                    // since certificates are NOT used in plaintext connections
                    continue;
                  }
                  if (database.rejectsSsl()
                      && (sslMode.verifyCertificate()
                      || hostname == Hostname.BAD)
                  ) {
                    // DB would reject SSL connection, so it makes no sense to test cases like verify-full
                    continue;
                  }
                  for (GSSEncMode gssEncMode : GSSEncMode.values()) {
                    if (gssEncMode == GSSEncMode.REQUIRE) {
                      // TODO: support gss tests in /certdir/pg_hba.conf
                      continue;
                    }
                    for (Role role : Role.VALUES) {
                      if (clientCertificate != ClientCertificate.EMPTY && role != Role.CLIENT_CERT_ROLE) {
                        // Skip client certificates (good, bad) for the other roles (md5, scram)
                        // We do not test mixed "client_cert + scram" auth for now.
                        // Client certificate auth requires username to be encoded within the CN,
                        // so we need to generate more certificates if we want to add such tests.
                        continue;
                      }
                      if (serverVersion.getVersionNum() < ServerVersion.v10.getVersionNum() && role != Role.CLIENT_CERT_ROLE) {
                        // PostgreSQL <10 supports only boolean password_encryption,
                        // so it makes no sense testing extra md5/scram roles for 9.x
                        continue;
                      }
                      if (channelBinding == ChannelBinding.REQUIRE && role == Role.CLIENT_CERT_ROLE
                          && database != TestDatabase.certdb) {
                        // TODO: currently, CLIENT_CERT_ROLE uses "test" user, and we do not control password_encryption
                        //   for it. We should generate more certificates (e.g. for scram_... users) then we can enable
                        //   test that uses both channelBinding and client certificate auth at the same time.
                        // For certdb we know the connection would fail with
                        // "server skipped the authorization", and we assert the error.
                        continue;
                      }
                      tests.add(
                          new Object[]{hostname, database, sslMode, channelBinding, sslNegotiation,
                              clientCertificate, rootCertificate, gssEncMode, role});
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    return tests;
  }

  @BeforeAll
  static void createRoles() throws SQLException {
    TestUtil.assumeSslTestsEnabled();
    try (Connection conn = TestUtil.openPrivilegedDB()) {
      if (!TestUtil.haveMinimumServerVersion(conn, ServerVersion.v10)) {
        // PostgreSQL <10 supports only boolean values for password_encryption, so we don't
        // create extra roles
        return;
      }
      for (Role role : Role.VALUES) {
        if (role == Role.CLIENT_CERT_ROLE) {
          continue;
        }
        TestUtil.execute(conn, "SET password_encryption = '" + role.passwordEncryption + "'");
        TestUtil.execute(conn, "DROP ROLE IF EXISTS " + role.username);
        TestUtil.execute(conn, "CREATE ROLE " + role.username + " WITH LOGIN PASSWORD '" + role.getPassword() + "'");
      }
    }
  }

  @AfterAll
  static void dropRoles() throws SQLException {
    try (Connection conn = TestUtil.openPrivilegedDB()) {
      for (Role role : Role.VALUES) {
        if (role == Role.CLIENT_CERT_ROLE) {
          continue;
        }
        TestUtil.execute(conn, "DROP ROLE IF EXISTS " + role.username);
      }
    }
  }

  private static boolean contains(@Nullable String value, String substring) {
    return value != null && value.contains(substring);
  }

  private static void assertClientCertRequired(@Nullable SQLException e, String caseName) {
    if (e == null) {
      fail(caseName + " should result in failure of client validation");
    }
    assertEquals(PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), e.getSQLState(), caseName + " ==> CONNECTION_FAILURE is expected");
  }

  private void checkErrorCodes(@Nullable SQLException e) {
    if (e != null && e.getCause() instanceof FileNotFoundException
        && clientRootCertificate != ClientRootCertificate.EMPTY) {
      fail("FileNotFoundException => it looks like a configuration failure");
    }

    if (clientRootCertificate == ClientRootCertificate.EMPTY
        && (sslmode == SslMode.VERIFY_CA || sslmode == SslMode.VERIFY_FULL)) {
      String caseName = "rootCertificate is missing and sslmode=" + sslmode;
      if (e == null) {
        fail(caseName + " should result in FileNotFound exception for root certificate");
      }
      assertEquals(PSQLState.CONNECTION_FAILURE.getState(), e.getSQLState(), caseName + " ==> CONNECTION_FAILURE is expected");
      FileNotFoundException fnf = findCause(e, FileNotFoundException.class);
      if (fnf == null) {
        fail(caseName + " ==> FileNotFoundException should be present in getCause chain");
      }
      return;
    }

    if (db.requiresSsl() && sslmode == SslMode.DISABLE) {
      String caseName = "sslmode=DISABLE and database " + db + " requires SSL";
      if (e == null) {
        fail(caseName + " should result in connection failure");
      }
      assertEquals(PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), e.getSQLState(), caseName + " ==> INVALID_AUTHORIZATION_SPECIFICATION is expected");
      return;
    }

    if (db.rejectsSsl() && sslmode.requireEncryption()) {
      String caseName =
          "database " + db + " rejects SSL, and sslmode " + sslmode + " requires encryption";
      if (e == null) {
        fail(caseName + " should result in connection failure");
      }
      assertEquals(PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), e.getSQLState(), caseName + " ==> INVALID_AUTHORIZATION_SPECIFICATION is expected");
      return;
    }

    // Server certificate, server hostname, and client certificate can be validated in any order
    // So we have three validators and expect at least one of them to match
    List<AssertionError> errors = null;
    try {
      if (assertServerCertificate(e)) {
        return;
      }
    } catch (AssertionError ae) {
      if (e != null) {
        ae.addSuppressed(e);
      }
      errors = addError(errors, ae);
    }

    try {
      if (assertServerHostname(e)) {
        return;
      }
    } catch (AssertionError ae) {
      if (e != null) {
        ae.addSuppressed(e);
      }
      errors = addError(errors, ae);
    }

    try {
      if (assertClientCertificate(e)) {
        return;
      }
    } catch (AssertionError ae) {
      if (e != null) {
        ae.addSuppressed(e);
      }
      errors = addError(errors, ae);
    }

    try {
      if (assertChannelBinding(e)) {
        return;
      }
    } catch (AssertionError ae) {
      if (e != null) {
        ae.addSuppressed(e);
      }
      errors = addError(errors, ae);
    }

    if (sslmode == SslMode.ALLOW && db.requiresSsl()) {
      // Allow tries to connect with non-ssl first, and it always throws the first error even after try SSL.
      // "If SSL was expected to fail" (e.g. invalid certificate), and db requiresSsl, then ALLOW
      // should fail as well
      String caseName =
          "sslmode=ALLOW and db " + db + " requires SSL, and there are expected SSL failures";
      if (errors == null) {
        if (e != null) {
          fail(caseName + " ==> connection should be upgraded to SSL with no failures");
        }
      } else {
        try {
          if (e == null) {
            fail(caseName + " ==> connection should fail");
          }
          assertEquals(PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), e.getSQLState(), caseName + " ==> INVALID_AUTHORIZATION_SPECIFICATION is expected");
        } catch (AssertionError er) {
          for (AssertionError error : errors) {
            er.addSuppressed(error);
          }
          throw er;
        }
      }
      // ALLOW is ok
      return;
    }

    if (errors == null) {
      if (e == null) {
        // Assume "no exception" was expected.
        // The cases like "successfully connected in sslmode=DISABLE to SSLONLY db"
        // should be handled with assertions above
        return;
      }
      fail("SQLException present when it was not expected");
    }

    AssertionError firstError = errors.get(0);
    if (errors.size() == 1) {
      throw firstError;
    }

    for (int i = 1; i < errors.size(); i++) {
      AssertionError error = errors.get(i);
      firstError.addSuppressed(error);
    }

    throw firstError;
  }

  private static List<AssertionError> addError(@Nullable List<AssertionError> errors, AssertionError ae) {
    if (errors == null) {
      errors = new ArrayList<>();
    }
    errors.add(ae);
    return errors;
  }

  /**
   * Checks server certificate validation error.
   *
   * @param e connection exception or null if no exception
   * @return true when validation pass, false when the case is not applicable
   * @throws AssertionError when exception does not match expectations
   */
  private boolean assertServerCertificate(@Nullable SQLException e) {
    if (clientRootCertificate == ClientRootCertificate.GOOD
        || (sslmode != SslMode.VERIFY_CA && sslmode != SslMode.VERIFY_FULL)) {
      return false;
    }

    String caseName = "Server certificate is " + clientRootCertificate + " + sslmode=" + sslmode;
    if (e == null) {
      fail(caseName + " should result in failure of server validation");
    }

    assertEquals(PSQLState.CONNECTION_FAILURE.getState(), e.getSQLState(), caseName + " ==> CONNECTION_FAILURE is expected");
    CertPathValidatorException validatorEx = findCause(e, CertPathValidatorException.class);
    if (validatorEx == null) {
      fail(caseName + " ==> exception should be caused by CertPathValidatorException,"
          + " but no CertPathValidatorException is present in the getCause chain");
    }
    assertEquals("NO_TRUST_ANCHOR", validatorEx.getReason().toString(), caseName + " ==> CertPathValidatorException.getReason");
    return true;
  }

  /**
   * Checks hostname validation error.
   *
   * @param e connection exception or null if no exception
   * @return true when validation pass, false when the case is not applicable
   * @throws AssertionError when exception does not match expectations
   */
  private boolean assertServerHostname(@Nullable SQLException e) {
    if (sslmode != SslMode.VERIFY_FULL || host != Hostname.BAD) {
      return false;
    }

    String caseName = "VERIFY_FULL + hostname that does not match server certificate";
    if (e == null) {
      fail(caseName + " ==> CONNECTION_FAILURE expected");
    }
    assertEquals(PSQLState.CONNECTION_FAILURE.getState(), e.getSQLState(), caseName + " ==> CONNECTION_FAILURE is expected");
    String message = e.getMessage();
    if (message == null || !message.contains("PgjdbcHostnameVerifier")) {
      fail(caseName + " ==> message should contain"
          + " 'PgjdbcHostnameVerifier'. Actual message is " + message);
    }
    return true;
  }

  /**
   * Returns true if the error is expected.
   * @param e sql exception to analyze, or null if no exception happened during connect
   * @return true if the error is expected.
   */
  private boolean assertChannelBinding(@Nullable SQLException e) {
    if (channelBinding != ChannelBinding.REQUIRE) {
      // So far we expect errors only with channelBinding=require
      return false;
    }

    if (sslmode == SslMode.DISABLE) {
      String caseName = "channelBinding=require + sslmode=disable";
      if (e == null) {
        fail(caseName + " ==> CONNECTION_REJECTED expected");
      }
      assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState(), caseName + " ==> CONNECTION_REJECTED is expected as channelBinding requires TLS");
      return true;
    }

    if (db.rejectsSsl()) {
      String caseName = "channelBinding=require + db.rejectsSsl()";
      if (e == null) {
        fail(caseName + " ==> CONNECTION_REJECTED expected");
      }
      if (sslmode == SslMode.PREFER && e.getSQLState().equals(PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState())
          && e.getMessage().contains("pg_hba.conf")) {
        // It is fine for the connection to fail as follows:
        //   FATAL: no pg_hba.conf entry for host "192.168.107.1", user "test", database "hostnossldb", SSL encryption
        return true;
      }
      assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState(), caseName + " ==> CONNECTION_REJECTED is expected as channelBinding requires TLS");
      return true;
    }

    if (sslmode == SslMode.ALLOW && !db.requiresSsl()) {
      String caseName = "channelBinding=require + sslMode=allow + !db.requiresSsl()";
      if (e == null) {
        fail(caseName + " ==> CONNECTION_REJECTED expected");
      }
      assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState(), caseName + " ==> CONNECTION_REJECTED is expected as channelBinding requires TLS");
      return true;
    }

    if (db == TestDatabase.certdb) {
      String caseName = "channelBinding=require + db=certdb";
      if (e == null) {
        fail(caseName + " ==> CONNECTION_REJECTED expected");
      }
      assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState(), caseName + " ==> CONNECTION_REJECTED is expected as channelBinding requires SCRAM auth type, not cert");
      return true;
    }

    if (clientRole == Role.MD5_ROLE) {
      String caseName = "channelBinding=require + authType=md5";
      if (e == null) {
        fail(caseName + " ==> CONNECTION_REJECTED expected");
      }
      assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState(), caseName + " ==> CONNECTION_REJECTED is expected as channelBinding requires SCRAM auth type, not md5");
      return true;
    }

    return false;
  }

  /**
   * Checks client certificate validation error.
   *
   * @param e connection exception or null if no exception
   * @return true when validation pass, false when the case is not applicable
   * @throws AssertionError when exception does not match expectations
   */
  private boolean assertClientCertificate(@Nullable SQLException e) {
    if (db.requiresClientCert() && clientCertificate == ClientCertificate.EMPTY) {
      String caseName =
          "client certificate was not sent and database " + db + " requires client certificate";
      assertClientCertRequired(e, caseName);
      return true;
    }

    if (clientCertificate != ClientCertificate.BAD) {
      return false;
    }
    if (!db.requiresSsl() && channelBinding != ChannelBinding.REQUIRE
        && (sslmode == SslMode.DISABLE || sslmode == SslMode.ALLOW)) {
      // Allow plaintext connection when sslMode=allow|disable and no channel binding required
      return false;
    }
    // Server verifies certificate no matter how it is configured, so sending BAD one
    // is doomed to fail
    String caseName = "BAD client certificate, and database " + db + " requires one";
    if (e == null) {
      fail(caseName + " should result in failure of client validation");
    }
    // Note: Java's SSLSocket handshake does NOT process alert messages
    // even if they are present on the wire. This looks like a perfectly valid
    // handshake, however, the subsequent read from the stream (e.g. during startup
    // message) discovers the alert message (e.g. "Received fatal alert: decrypt_error")
    // and converts that to exception.
    // That is why "CONNECTION_UNABLE_TO_CONNECT" is listed here for BAD client cert.
    // Ideally, handshake failure should be detected during the handshake, not after sending the startup
    // message
    if (!PSQLState.CONNECTION_FAILURE.getState().equals(e.getSQLState())
        && !(clientCertificate == ClientCertificate.BAD
        && PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState().equals(e.getSQLState()))
    ) {
      fail(caseName + " ==> CONNECTION_FAILURE(08006)"
              + " or CONNECTION_UNABLE_TO_CONNECT(08001) is expected"
              + ", got " + e.getSQLState());
    }

    // Three exceptions are possible
    // SSLHandshakeException: Received fatal alert: unknown_ca
    // EOFException
    // SocketException: broken pipe (write failed)
    // Invalid argument which is a result of calling TcpNoDelay on a broken pipe
    // decrypt_error does not look to be a valid case, however, we allow it for now
    // SSLHandshakeException: Received fatal alert: decrypt_error

    SocketException brokenPipe = findCause(e, SocketException.class);
    if (brokenPipe != null) {
      if (contains(brokenPipe.getMessage(), "Broken pipe")) {
        return true;
      }
      if (contains(brokenPipe.getMessage(), "Invalid argument")) {
        return true;
      }
      fail(
          caseName + " ==> Invalid Exception"
                + ", actual exception was " + brokenPipe.getMessage());

      return true;
    }

    EOFException eofException = findCause(e, EOFException.class);
    if (eofException != null) {
      return true;
    }

    SSLHandshakeException handshakeException = findCause(e, SSLHandshakeException.class);
    if (handshakeException != null) {
      final String handshakeMessage = handshakeException.getMessage();
      if (!contains(handshakeMessage, "unknown_ca")
          && !contains(handshakeMessage, "decrypt_error")) {
        fail(
            caseName
                + " ==> server should have terminated the connection (expected 'unknown_ca' or 'decrypt_error')"
                + ", actual exception was " + handshakeMessage);
      }
      return true;
    }

    fail(caseName + " ==> exception should be caused by SocketException(broken pipe)"
        + " or EOFException,"
        + " or SSLHandshakeException. No exceptions of such kind are present in the getCause chain");
    return false;
  }

  private static <@Nullable T extends Throwable> T findCause(@Nullable Throwable t,
      Class<T> cause) {
    while (t != null) {
      if (cause.isInstance(t)) {
        return (T) t;
      }
      t = t.getCause();
    }
    return null;
  }

  @Test
  void run() throws SQLException {
    Properties props = new Properties();
    if (clientRole != Role.CLIENT_CERT_ROLE) {
      PGProperty.USER.set(props, clientRole.username);
      PGProperty.PASSWORD.set(props, clientRole.getPassword());
    }
    TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, host.value);
    TestUtil.setTestUrlProperty(props, PGProperty.PG_DBNAME, db.toString());
    PGProperty.SSL_MODE.set(props, sslmode.value);
    PGProperty.SSL_NEGOTIATION.set(props, sslNegotiation.value());
    PGProperty.CHANNEL_BINDING.set(props, channelBinding.value);
    PGProperty.GSS_ENC_MODE.set(props, gssEncMode.value);
    if (clientCertificate == ClientCertificate.EMPTY) {
      PGProperty.SSL_CERT.set(props, "");
      PGProperty.SSL_KEY.set(props, "");
    } else {
      PGProperty.SSL_CERT.set(props, TestUtil.getSslTestCertPath(clientCertificate.fileName + ".crt"));
      PGProperty.SSL_KEY.set(props, TestUtil.getSslTestCertPath(clientCertificate.fileName + ".p12"));
    }
    if (clientRootCertificate == ClientRootCertificate.EMPTY) {
      PGProperty.SSL_ROOT_CERT.set(props, "");
    } else {
      PGProperty.SSL_ROOT_CERT.set(props, TestUtil.getSslTestCertPath(clientRootCertificate.fileName + ".crt"));
    }
    try (Connection conn = TestUtil.openDB(props)) {
      boolean sslUsed = TestUtil.queryForBoolean(conn, "SELECT ssl_is_used()");
      // Verify the successful connection (it might be the connection was supposed to fail)
      if (sslmode == SslMode.ALLOW) {
        assertEquals(db.requiresSsl(), sslUsed, "SSL should be used if the DB requires SSL");
      } else {
        assertEquals(sslmode != SslMode.DISABLE && !db.rejectsSsl(), sslUsed, "SSL should be used unless it is disabled or the DB rejects it");
      }
      if (channelBinding == ChannelBinding.REQUIRE) {
        assertTrue(sslUsed, "channelBinding=require requires SSL");
      }
      checkErrorCodes(null);
    } catch (SQLException e) {
      // Verify the failed connections (it might be certain failures were expected)
      try {
        // Note that checkErrorCodes throws AssertionError for unexpected cases
        checkErrorCodes(e);
      } catch (AssertionError ae) {
        // Make sure the original SQLException is printed as well even in case of AssertionError
        ae.initCause(e);
        throw ae;
      }
    }
  }
}
