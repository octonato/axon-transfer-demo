package com.example.transfer.domain;

public record TransferState(String transferId, Transfer transfer, TransferStatus status) {}
