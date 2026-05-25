/*
/* Copyright 2018-2026 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.client.utils.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import org.junit.jupiter.api.Test;

class TnsParserTest {

  @Test
  void detectsTnsDescriptor() {
    assertThat(TnsParser.isTnsDescriptor("(DESCRIPTION=(ADDRESS=(HOST=h)))")).isTrue();
    assertThat(TnsParser.isTnsDescriptor("(description=(address=(host=h)))")).isTrue();
    assertThat(TnsParser.isTnsDescriptor("hostname:1521/service")).isFalse();
  }

  @Test
  void convertsSimpleDescriptorWithServiceName() throws URISyntaxException {
    String descriptor =
        "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=hostname)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=orcl.example.com)))";
    assertThat(TnsParser.toEzConnect(descriptor)).isEqualTo("hostname:1521/orcl.example.com");
  }

  @Test
  void convertsSidDescriptor() throws URISyntaxException {
    String descriptor =
        "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=hostname)(PORT=1522))(CONNECT_DATA=(SID=ORCL)))";
    assertThat(TnsParser.toEzConnect(descriptor)).isEqualTo("hostname:1522/ORCL");
  }

  @Test
  void convertsInstanceNameDescriptor() throws URISyntaxException {
    String descriptor =
        "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=hostname)(PORT=1521))(CONNECT_DATA=(INSTANCE_NAME=ORCL)))";
    assertThat(TnsParser.toEzConnect(descriptor)).isEqualTo("hostname:1521/ORCL");
  }

  @Test
  void usesDefaultPortWhenMissing() throws URISyntaxException {
    String descriptor =
        "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=hostname))(CONNECT_DATA=(SERVICE_NAME=svc)))";
    assertThat(TnsParser.toEzConnect(descriptor)).isEqualTo("hostname:1521/svc");
  }

  @Test
  void omitsConnectDataWhenAbsent() throws URISyntaxException {
    String descriptor = "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=hostname)(PORT=1521)))";
    assertThat(TnsParser.toEzConnect(descriptor)).isEqualTo("hostname:1521");
  }

  @Test
  void selectsFirstAddressAfterLowercasingAndOrdering() throws URISyntaxException {
    String descriptor =
        "(DESCRIPTION="
            + "(ADDRESS_LIST=(LOAD_BALANCE=ON)"
            + "(ADDRESS=(PROTOCOL=tcp)(HOST=SalesServer2)(PORT=1522))"
            + "(ADDRESS=(PROTOCOL=tcp)(HOST=SalesServer1)(PORT=1521))"
            + "(ADDRESS=(PROTOCOL=tcp)(HOST=SalesServer3)(PORT=1523)))"
            + "(CONNECT_DATA=(SERVICE_NAME=sales.us.example.com)))";
    // hosts lowercased and ordered -> salesserver1 wins, keeping its own port
    assertThat(TnsParser.toEzConnect(descriptor))
        .isEqualTo("salesserver1:1521/sales.us.example.com");
  }

  @Test
  void throwsWhenNoHostPresent() {
    assertThatThrownBy(() -> TnsParser.toEzConnect("(DESCRIPTION=(CONNECT_DATA=(SID=ORCL)))"))
        .isInstanceOf(URISyntaxException.class);
  }
}
