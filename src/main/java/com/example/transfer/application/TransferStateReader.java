package com.example.transfer.application;

import com.example.transfer.domain.TransferState;
import com.example.transfer.domain.TransferWorkflowPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.query.api.WorkflowStateQuery;
import io.axoniq.framework.workflow.runtime.api.manager.WorkflowManager;
import java.util.Objects;
import java.util.Optional;
import org.axonframework.common.configuration.Configuration;
import org.springframework.stereotype.Component;

@Component
public class TransferStateReader {
  private final Configuration configuration;
  private final ObjectMapper json;

  public TransferStateReader(Configuration configuration, ObjectMapper json) {
    this.configuration = configuration;
    this.json = json;
  }

  public Optional<TransferState> find(String transferId) {
    var manager = Objects.requireNonNull(configuration.getComponents(WorkflowManager.class)
        .get("WorkflowManager[" + SimpleWorkflowContext.class.getSimpleName() + "]"),
        "MoneyTransfer workflow manager is not configured");
    var state = manager.findOne(WorkflowStateQuery.byWorkflowId(transferId)).singleState().join();
    return Optional.ofNullable(state)
        .map(workflow -> json.convertValue(workflow.payload(), TransferWorkflowPayload.class).toState());
  }
}
