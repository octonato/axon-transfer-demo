package com.example.transfer.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WalletService {
  private static final Logger LOG = LoggerFactory.getLogger(WalletService.class);
  private final WalletRepository wallets;

  public WalletService(WalletRepository wallets) {
    this.wallets = wallets;
  }

  public int withdraw(String walletId, int amount) {
    validateAmount(amount);
    int updated = wallets.updateBalance(walletId, balance -> {
      if (balance < amount) {
        throw new IllegalArgumentException("Insufficient funds in wallet " + walletId);
      }
      return balance - amount;
    });
    LOG.info("Withdrawn {} from wallet {}; balance={}", amount, walletId, updated);
    return updated;
  }

  public int deposit(String walletId, int amount) {
    validateAmount(amount);
    int updated = wallets.updateBalance(walletId, balance -> Math.addExact(balance, amount));
    LOG.info("Deposited {} into wallet {}; balance={}", amount, walletId, updated);
    return updated;
  }

  public int getBalance(String walletId) {
    return wallets.getBalance(walletId);
  }

  private static void validateAmount(int amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("Amount must be positive");
    }
  }
}
