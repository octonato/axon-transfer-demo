package com.example.transfer.application;

import com.example.transfer.domain.TransferStatus;
import com.example.transfer.domain.TransferWorkflowPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.transfer.events.TransferCompleted;
import com.example.transfer.events.TransferRequested;
import com.example.transfer.events.TransferWithdrawn;
import io.axoniq.framework.workflow.annotation.Workflow;
import io.axoniq.framework.workflow.dsl.api.StepFailedException;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import java.time.Duration;

import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TransferWorkflow {
  private final WalletService wallets;
  private final ObjectMapper json;

  private final Logger logger = LoggerFactory.getLogger(TransferWorkflow.class);

  public TransferWorkflow(WalletService wallets, ObjectMapper json) {
    this.wallets = wallets;
    this.json = json;
  }

  @Workflow(
      workflowName = "MoneyTransfer",
      idProperty = "transferId",
      startOnEventClass = TransferRequested.class,
      workflowNamespace = "com.example.transfer")
  public void transfer(SimpleWorkflowContext context) {

    var transferId = context.workflowId();
    var payload = json.convertValue(context.workflowPayload(), TransferWorkflowPayload.class);
    context.setPayload("initializeTransfer", payload.withStatus(TransferStatus.STARTED));

    try {
      logger.info("Transfer started [{}]", transferId);

      context.awaitExecute("withdraw", Integer.class, () -> wallets.withdraw(payload.from(), payload.amount()));
      context.setPayload("withdrawalSucceeded", payload.withStatus(TransferStatus.WITHDRAW_SUCCEEDED));
      context.awaitPublish("recordWithdrawal",
          new TransferWithdrawn(payload.transferId(), payload.from(), payload.to(), payload.amount()));

      logger.info("Withdraw completed [{}]", transferId);

      if (payload.amount() >= 200) {
        logger.info("Taking a break... [{}]", transferId);
        // The 5.4.0 sleep(String, Duration) helper does not await the timer.
        context.sleep("pauseAfterWithdraw", step -> step.timeout(Duration.ofSeconds(20))).await();
        logger.info("Back to work [{}]", transferId);
      }

      context.awaitExecute("deposit", Integer.class, () -> wallets.deposit(payload.to(), payload.amount()));
      context.setPayload("transferCompleted", payload.withStatus(TransferStatus.COMPLETED));
      context.awaitPublish("recordCompletion",
          new TransferCompleted(payload.transferId(), payload.from(), payload.to(), payload.amount()));
      logger.info("Deposit completed [{}]", transferId);

    } catch (StepFailedException failure) {
      context.setPayload("transferFailed", payload.withStatus(TransferStatus.FAILED));
      throw failure;
    }
  }
}
