package com.example.transfer.events;

public record TransferCompleted(String transferId, String from, String to, int amount) {}
