/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

public class TypesTest extends BaseTest4 {

  private Connection conn;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    conn = con;
    Statement stmt = conn.createStatement();
    stmt.execute(
        "CREATE OR REPLACE FUNCTION return_bool(boolean) RETURNS boolean AS 'BEGIN RETURN $1; END;' LANGUAGE plpgsql");
    stmt.close();
  }

  @Override
  public void tearDown() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP FUNCTION return_bool(boolean)");
    stmt.close();
    super.tearDown();
  }

  @Test
  public void testPreparedBoolean() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?,?,?,?");
    pstmt.setNull(1, Types.BOOLEAN);
    pstmt.setObject(2, null, Types.BOOLEAN);
    pstmt.setBoolean(3, true);
    pstmt.setObject(4, Boolean.FALSE);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertFalse(rs.getBoolean(1));
    assertTrue(rs.wasNull());
    assertNull(rs.getObject(2));
    assertTrue(rs.getBoolean(3));
    // Only the V3 protocol return will be strongly typed.
    // The V2 path will return a String because it doesn't know
    // any better.
    if (preferQueryMode != PreferQueryMode.SIMPLE) {
      assertFalse(((Boolean) rs.getObject(4)).booleanValue());
    }
  }

  @Test
  public void testPreparedByte() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?,?");
    pstmt.setByte(1, (byte) 1);
    pstmt.setObject(2, (byte) 2);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals((byte) 1, rs.getByte(1));
    assertFalse(rs.wasNull());
    assertEquals((byte) 2, rs.getByte(2));
    assertFalse(rs.wasNull());
    rs.close();
    pstmt.close();
  }

  @Test
  public void testCallableBoolean() throws SQLException {
    assumeCallableStatementsSupported();
    CallableStatement cs = conn.prepareCall("{? = call return_bool(?)}");
    cs.registerOutParameter(1, Types.BOOLEAN);
    cs.setBoolean(2, true);
    cs.execute();
    assertTrue(cs.getBoolean(1));
    cs.close();
  }

  @Test
  public void testUnknownType() throws SQLException {
    Statement stmt = conn.createStatement();

    ResultSet rs = stmt.executeQuery("select 'foo1' as icon1, 'foo2' as icon2 ");
    assertTrue(rs.next());
    assertEquals("foo1", rs.getString("icon1"), "failed");
    assertEquals("foo2", rs.getString("icon2"), "failed");
  }

}
