package com.example.transfer.events;

public record TransferRequested(String transferId, String from, String to, int amount) {}
