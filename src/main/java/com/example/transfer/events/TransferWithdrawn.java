package com.example.transfer.events;

public record TransferWithdrawn(String transferId, String from, String to, int amount) {}
