/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.PGProperty;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * routines to support PG properties
 */
public class PGPropertyUtil {

  private static final Logger LOGGER = Logger.getLogger(PGPropertyUtil.class.getName());

  /**
   * converts port String to Integer
   *
   * @param portStr value of port
   * @return value of port or null
   */
  private static @Nullable Integer convertPgPortToInt(String portStr) {
    try {
      int port = Integer.parseInt(portStr);
      if (port < 1 || port > 65535) {
        LOGGER.log(Level.WARNING, "JDBC URL port: {0} not valid (1:65535) ", portStr);
        return null;
      }
      return port;
    } catch (NumberFormatException ignore) {
      LOGGER.log(Level.WARNING, "JDBC URL invalid port number: {0}", portStr);
      return null;
    }
  }

  /**
   * Validate properties. Goal is to detect inconsistencies and report understandable messages
   *
   * @param properties properties
   * @return false if errors found
   */
  public static boolean propertiesConsistencyCheck(Properties properties) {
    //
    String hosts = PGProperty.HOST.get(properties);
    if (hosts == null) {
      LOGGER.log(Level.WARNING, "Property [{0}] can not be null", PGProperty.HOST.getName());
      return false;
    }
    String ports = PGProperty.PORT.get(properties);
    if (ports == null) {
      LOGGER.log(Level.WARNING, "Property [{0}] can not be null", PGProperty.PORT.getName());
      return false;
    }

    // check port values
    for (String portStr : ports.split(",")) {
      if (PGPropertyUtil.convertPgPortToInt(portStr) == null) {
        return false;
      }
    }

    // check count of hosts and count of ports
    int hostCount = hosts.split(",").length;
    int portCount = ports.split(",").length;
    if (hostCount != portCount) {
      LOGGER.log(Level.WARNING, "Properties [{0}] [{1}] must have same amount of values",
          new Object[]{PGProperty.HOST.getName(), PGProperty.PORT.getName()});
      LOGGER.log(Level.WARNING, "Property [{0}] ; value [{1}] ; count [{2}]",
          new Object[]{PGProperty.HOST.getName(), hosts, hostCount});
      LOGGER.log(Level.WARNING, "Property [{0}] ; value [{1}] ; count [{2}]",
          new Object[]{PGProperty.PORT.getName(), ports, portCount});
      return false;
    }
    //
    return true;
  }

  /**
   * translate PGSERVICEFILE keys host, port, dbname
   *
   * @param serviceKey key in pg_service.conf
   * @return translated property or the same value if translation is not needed
   */
  // translate PGSERVICEFILE keys host, port, dbname
  public static String translatePGServiceToPGProperty(String serviceKey) {
    return serviceKey;
  }

  /**
   * translate PGSERVICEFILE keys host, port, dbname
   *
   * @param propertyKey postgres property
   * @return translated property or the same value if translation is not needed
   */
  public static String translatePGPropertyToPGService(String propertyKey) {
    return propertyKey;
  }
}
