package com.example.transfer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalletServiceTest {
  @TempDir Path directory;

  private WalletService wallets(Map<String, Integer> initialBalances) {
    return new WalletService(new WalletRepository(directory, new ObjectMapper(), initialBalances));
  }

  @Test
  void movesMoney() {
    var wallets = wallets(Map.of("a", 100, "b", 100));
    wallets.withdraw("a", 10);
    wallets.deposit("b", 10);
    assertEquals(90, wallets.getBalance("a"));
    assertEquals(110, wallets.getBalance("b"));
  }

  @Test
  void rejectsInvalidOperations() {
    var wallets = wallets(Map.of("a", 10));
    assertThrows(IllegalArgumentException.class, () -> wallets.withdraw("missing", 1));
    assertThrows(IllegalArgumentException.class, () -> wallets.deposit("a", 0));
    assertThrows(IllegalArgumentException.class, () -> wallets.withdraw("a", 11));
    assertEquals(10, wallets(Map.of("a", 999)).getBalance("a"));
  }

  @Test
  void preservesBalancesAcrossRestartInSeparateWalletFiles() throws Exception {
    var wallets = wallets(Map.of("a", 100, "b", 100));
    wallets.withdraw("a", 10);
    wallets.deposit("b", 10);

    var restarted = wallets(Map.of("a", 10_000, "b", 10_000));
    assertEquals(90, restarted.getBalance("a"));
    assertEquals(110, restarted.getBalance("b"));
    var json = new ObjectMapper();
    assertEquals(json.readTree("{\"walletId\":\"a\",\"balance\":90}"),
        json.readTree(directory.resolve("a.json").toFile()));
    assertEquals(json.readTree("{\"walletId\":\"b\",\"balance\":110}"),
        json.readTree(directory.resolve("b.json").toFile()));
  }

  @Test
  void refusesCorruptExistingWalletInsteadOfResettingIt() throws Exception {
    var file = directory.resolve("a.json");
    for (var content : new String[] {"broken", "{}", "{\"walletId\":\"a\",\"balance\":1.5}"}) {
      Files.writeString(file, content);
      assertThrows(UncheckedIOException.class, () -> wallets(Map.of("a", 10_000)));
      assertEquals(content, Files.readString(file));
    }
  }

  @Test
  void serializesConcurrentBalanceUpdates() throws Exception {
    var wallets = wallets(Map.of("a", 100));
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var results = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 20; i++) {
        results.add(executor.submit(() -> wallets.deposit("a", 1)));
      }
      for (var result : results) result.get();
    }
    assertEquals(120, wallets(Map.of("a", 0)).getBalance("a"));
  }

  @Test
  void rejectsWalletIdsThatCouldEscapeTheDirectory() {
    var wallets = wallets(Map.of("a", 10));
    assertThrows(IllegalArgumentException.class, () -> wallets.getBalance("../outside"));
  }
}
