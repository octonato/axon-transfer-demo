package com.example.transfer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.awaitility.Awaitility.await;

import com.example.transfer.events.TransferRequested;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer;
import io.axoniq.framework.workflow.configuration.WorkflowModule;
import io.axoniq.framework.workflow.dsl.api.WorkflowStatus;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.runtime.test.fixture.GivenWhen;
import io.axoniq.framework.workflow.runtime.test.fixture.Then;
import io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestFixture;
import io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestDriver;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransferWorkflowTest {
  @TempDir Path directory;
  private WorkflowTestFixture<GivenWhen.Phase, Then.Phase> fixture;

  @AfterEach
  void tearDown() {
    if (fixture != null) {
      fixture.then().stop();
    }
  }

  @Test
  void completesTheTwoDurableActions() {
    var wallets = wallets();
    fixture = fixture(new TransferWorkflow(wallets, new ObjectMapper()));

    fixture.when()
        .publishEvent(new TransferRequested("1", "a", "b", 199))
        .execute("withdraw")
        .execute("deposit");

    fixture.then()
        .stepsPassed("withdraw", "recordWithdrawal", "deposit", "recordCompletion")
        .workflowFinished(WorkflowStatus.COMPLETED);
  }

  @Test
  void pausesAfterWithdrawalBeforeDeposit() {
    var wallets = wallets();
    fixture = fixture(new TransferWorkflow(wallets, new ObjectMapper()));

    fixture.when()
        .publishEvent(new TransferRequested("1", "a", "b", 200))
        .execute("withdraw");

    fixture.then()
        .stepsPassed("withdraw", "recordWithdrawal")
        .waitingIn("pauseAfterWithdraw")
        .workflowNotFinished();
    assertEquals(9_800, wallets.getBalance("a"));
    assertEquals(10_000, wallets.getBalance("b"));

  }

  private WalletService wallets() {
    return new WalletService(
        new WalletRepository(directory, new ObjectMapper(), Map.of("a", 10_000, "b", 10_000)));
  }

  @Test
  void liveExecutionWaitsTwentySecondsBetweenWithdrawalAndDeposit() {
    var withdrawnAt = new AtomicLong();
    var depositedAt = new AtomicLong();
    var wallets = new WalletService(new WalletRepository(directory, new ObjectMapper(),
        Map.of("a", 10_000, "b", 10_000))) {
      @Override
      public int withdraw(String walletId, int amount) {
        int balance = super.withdraw(walletId, amount);
        withdrawnAt.set(System.nanoTime());
        return balance;
      }

      @Override
      public int deposit(String walletId, int amount) {
        depositedAt.set(System.nanoTime());
        return super.deposit(walletId, amount);
      }
    };
    var workflow = new TransferWorkflow(wallets, new ObjectMapper());
    var module = WorkflowModule.defaults("TransferWorkflowLiveTest", SimpleWorkflowContext.class)
        .definition(d -> d.autodetected(c -> workflow));
    var driver = WorkflowTestDriver.live(module, configurer -> configurer.componentRegistry(
        registry -> registry.disableEnhancer(AxonServerConfigurationEnhancer.class)));
    try {
      driver.publishEvent(new TransferRequested("live-delay", "a", "b", 200));
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
          assertEquals(10_200, wallets.getBalance("b")));
      assertTrue(withdrawnAt.get() > 0);
      assertTrue(depositedAt.get() - withdrawnAt.get() >= Duration.ofSeconds(20).toNanos(),
          "Deposit must wait for the durable 20-second timer");
      driver.noExecution();
    } finally {
      driver.shutdown();
    }
  }

  private static WorkflowTestFixture<GivenWhen.Phase, Then.Phase> fixture(TransferWorkflow workflow) {
    WorkflowModule<SimpleWorkflowContext> module = WorkflowModule
        .defaults("TransferWorkflowTest", SimpleWorkflowContext.class)
        .definition(d -> d.autodetected(c -> workflow));
    return WorkflowTestFixture.of(module, configurer -> configurer.componentRegistry(
        registry -> registry.disableEnhancer(AxonServerConfigurationEnhancer.class)));
  }
}
