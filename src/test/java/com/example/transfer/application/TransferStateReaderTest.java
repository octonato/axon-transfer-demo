package com.example.transfer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.transfer.domain.Transfer;
import com.example.transfer.domain.TransferState;
import com.example.transfer.domain.TransferStatus;
import com.example.transfer.events.TransferRequested;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer;
import io.axoniq.framework.workflow.configuration.WorkflowModule;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.history.inmemory.MutableWorkflowHistoryRepository;
import io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestDriver;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransferStateReaderTest {
  @TempDir Path directory;
  private WorkflowTestDriver driver;
  private TransferStateReader stateReader;

  @AfterEach
  void tearDown() {
    if (driver != null) driver.shutdown();
  }

  @Test
  void unknownWorkflowIsAbsent() {
    start();
    assertTrue(stateReader.find("missing").isEmpty());
  }

  @Test
  void readsLiveWorkflowBeforeAndAfterWithdrawal() {
    start();
    driver.publishEvent(new TransferRequested("1", "a", "b", 200));
    driver.executionExists();
    assertEquals(expected(200, TransferStatus.STARTED), stateReader.find("1").orElseThrow());

    driver.executeStep("withdraw");
    driver.awaitEventually("withdrawal visible through workflow state", () ->
        assertEquals(expected(200, TransferStatus.WITHDRAW_SUCCEEDED), stateReader.find("1").orElseThrow()));
  }

  @Test
  void readsCompletedWorkflowFromStoreWithoutHistoryProjection() {
    start();
    driver.publishEvent(new TransferRequested("1", "a", "b", 10));
    driver.executeStep("withdraw");
    driver.executeStep("deposit");
    driver.noExecution();
    driver.workflowTestServices().configuration()
        .getComponents(MutableWorkflowHistoryRepository.class).values()
        .forEach(MutableWorkflowHistoryRepository::clear);

    assertEquals(expected(TransferStatus.COMPLETED), stateReader.find("1").orElseThrow());
  }

  @Test
  void readsBusinessFailureRecordedByWorkflow() {
    start();
    driver.publishEvent(new TransferRequested("1", "a", "b", 20_000));
    driver.executeStep("withdraw");
    driver.noExecution();

    assertEquals(new TransferState("1", new Transfer("a", "b", 20_000), TransferStatus.FAILED),
        stateReader.find("1").orElseThrow());
  }

  private void start() {
    var wallets = new WalletService(new WalletRepository(directory, new ObjectMapper(),
        Map.of("a", 10_000, "b", 10_000)));
    var workflow = new TransferWorkflow(wallets, new ObjectMapper());
    var module = WorkflowModule.defaults(SimpleWorkflowContext.class.getSimpleName(), SimpleWorkflowContext.class)
        .definition(d -> d.autodetected(c -> workflow));
    driver = WorkflowTestDriver.stepper(module, true, configurer -> configurer.componentRegistry(
        registry -> registry.disableEnhancer(AxonServerConfigurationEnhancer.class)));
    stateReader = new TransferStateReader(driver.workflowTestServices().configuration(), new ObjectMapper());
  }

  private static TransferState expected(TransferStatus status) {
    return expected(10, status);
  }

  private static TransferState expected(int amount, TransferStatus status) {
    return new TransferState("1", new Transfer("a", "b", amount), status);
  }
}
