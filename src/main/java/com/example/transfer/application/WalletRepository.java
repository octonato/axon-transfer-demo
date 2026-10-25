package com.example.transfer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {
  private final Path directory;
  private final ObjectMapper json;

  @Autowired
  public WalletRepository(
      ObjectMapper json, @Value("${demo.wallet-directory:target/wallets}") String directory) {
    this(Path.of(directory), json, Map.of("a", 10_000, "b", 10_000));
  }

  public WalletRepository(Path directory, ObjectMapper json, Map<String, Integer> initialBalances) {
    this.directory = directory;
    this.json = json;
    try {
      Files.createDirectories(directory);
      for (var entry : initialBalances.entrySet()) {
        if (!Files.exists(walletFile(entry.getKey()))) {
          save(entry.getKey(), entry.getValue());
        }
        getBalance(entry.getKey());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot initialize wallets in " + directory, e);
    }
  }

  public synchronized int getBalance(String walletId) {
    var file = walletFile(walletId);
    if (!Files.exists(file)) throw new IllegalArgumentException("Unknown wallet: " + walletId);
    try {
      var wallet = json.readTree(file.toFile());
      if (wallet == null || !walletId.equals(wallet.path("walletId").asText())
          || !wallet.path("balance").isIntegralNumber()
          || !wallet.path("balance").canConvertToInt()
          || wallet.path("balance").intValue() < 0) {
        throw new IOException("Invalid wallet data in " + file);
      }
      return wallet.path("balance").intValue();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read wallet " + walletId, e);
    }
  }

  public synchronized int updateBalance(String walletId, IntUnaryOperator update) {
    var balance = update.applyAsInt(getBalance(walletId));
    try {
      save(walletId, balance);
      return balance;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot save wallet " + walletId, e);
    }
  }

  private void save(String walletId, int balance) throws IOException {
    var file = walletFile(walletId);
    var temporary = Files.createTempFile(directory, walletId + "-", ".tmp");
    try {
      json.writeValue(temporary.toFile(), Map.of("walletId", walletId, "balance", balance));
      Files.move(temporary, file,
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private Path walletFile(String walletId) {
    if (walletId == null || !walletId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw new IllegalArgumentException("Invalid wallet ID: " + walletId);
    }
    return directory.resolve(walletId + ".json");
  }
}
