package com.example.transfer.domain;

public record TransferWorkflowPayload(
    String transferId, String from, String to, int amount, TransferStatus status) {

  public TransferWorkflowPayload {
    if (status == null) status = TransferStatus.STARTED;
  }

  public TransferWorkflowPayload withStatus(TransferStatus status) {
    return new TransferWorkflowPayload(transferId, from, to, amount, status);
  }

  public TransferState toState() {
    return new TransferState(transferId, new Transfer(from, to, amount), status);
  }
}
