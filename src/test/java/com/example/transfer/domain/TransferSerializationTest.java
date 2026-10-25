package com.example.transfer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TransferSerializationTest {
  @Test
  void roundTripsPublicState() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var expected = new TransferState("1", new Transfer("a", "b", 10), TransferStatus.COMPLETED);
    assertEquals(expected, mapper.readValue(mapper.writeValueAsBytes(expected), TransferState.class));
  }
}
