/*
/* Copyright 2018-2026 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.client.utils.jdbc;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Converts an Oracle TNS (Transparent Network Substrate) descriptor into the EZConnect format
 * {@code host:port/serviceName} so it can be processed by {@link OracleJdbcExtractor}.
 *
 * <p>TNS descriptors may declare multiple {@code (ADDRESS=...)} entries for load balancing or
 * failover. Since all addresses point to the same Oracle service and a dataset namespace cannot
 * contain commas, only a single address is kept: hosts are lowercased, ordered, and the first one
 * is selected. A warning is logged when more than one address is present.
 */
@Slf4j
public final class TnsParser {
  private static final String DEFAULT_PORT = "1521";

  private static final Pattern HOST_PATTERN =
      Pattern.compile("HOST\\s*=\\s*([^)\\s]+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern PORT_PATTERN =
      Pattern.compile("PORT\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern SERVICE_NAME_PATTERN =
      Pattern.compile("SERVICE_NAME\\s*=\\s*([^)\\s]+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern SID_PATTERN =
      Pattern.compile("\\bSID\\s*=\\s*([^)\\s]+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern INSTANCE_NAME_PATTERN =
      Pattern.compile("INSTANCE_NAME\\s*=\\s*([^)\\s]+)", Pattern.CASE_INSENSITIVE);

  private TnsParser() {}

  /**
   * Detects whether a connection string is an Oracle TNS descriptor.
   *
   * @param uri connection string (TNS descriptors start with a parenthesis group)
   * @return true when the string contains TNS keywords
   */
  public static boolean isTnsDescriptor(String uri) {
    String upper = uri.toUpperCase(Locale.ROOT);
    return upper.contains("DESCRIPTION") || upper.contains("ADDRESS") || upper.contains("CONNECT_DATA");
  }

  /**
   * Converts a TNS descriptor into EZConnect ({@code host:port/serviceName}).
   *
   * @param descriptor the TNS descriptor
   * @return EZConnect connection string
   * @throws URISyntaxException when no host can be extracted from the descriptor
   */
  public static String toEzConnect(String descriptor) throws URISyntaxException {
    List<String> hosts = matchAll(HOST_PATTERN, descriptor);
    if (hosts.isEmpty()) {
      throw new URISyntaxException(descriptor, "No HOST found in TNS descriptor");
    }

    List<String> ports = matchAll(PORT_PATTERN, descriptor);
    String ezConnect = selectAddress(hosts, ports);

    String connectData = extractConnectData(descriptor);
    if (connectData != null) {
      ezConnect = ezConnect + "/" + connectData;
    }
    return ezConnect;
  }

  /**
   * Pairs each host with its port (by declaration order), lowercases hosts, orders the addresses,
   * and selects the first one. HOST and PORT appear in order within each {@code (ADDRESS=...)}, so
   * the index-aligned pairing keeps a host with its own port.
   */
  private static String selectAddress(List<String> hosts, List<String> ports) {
    List<String> addresses = new ArrayList<>();
    for (int i = 0; i < hosts.size(); i++) {
      String port = i < ports.size() ? ports.get(i) : DEFAULT_PORT;
      addresses.add(hosts.get(i).toLowerCase(Locale.ROOT) + ":" + port);
    }
    if (addresses.size() > 1) {
      addresses.sort(String::compareTo);
      log.warn(
          "TNS descriptor declares multiple addresses {}; only the first ({}) is used for the dataset namespace",
          addresses,
          addresses.get(0));
    }
    return addresses.get(0);
  }

  private static String extractConnectData(String descriptor) {
    Matcher service = SERVICE_NAME_PATTERN.matcher(descriptor);
    if (service.find()) {
      return service.group(1);
    }
    Matcher sid = SID_PATTERN.matcher(descriptor);
    if (sid.find()) {
      return sid.group(1);
    }
    Matcher instance = INSTANCE_NAME_PATTERN.matcher(descriptor);
    if (instance.find()) {
      return instance.group(1);
    }
    return null;
  }

  private static List<String> matchAll(Pattern pattern, String input) {
    List<String> results = new ArrayList<>();
    Matcher matcher = pattern.matcher(input);
    while (matcher.find()) {
      results.add(matcher.group(1));
    }
    return results;
  }
}
