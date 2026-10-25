package com.example.transfer.web;

import com.example.transfer.application.TransferStateReader;
import com.example.transfer.application.WalletService;
import com.example.transfer.domain.Transfer;
import com.example.transfer.domain.TransferState;
import com.example.transfer.events.TransferRequested;
import java.util.List;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping
public class TransferController {
  private final EventGateway events;
  private final TransferStateReader transfers;
  private final WalletService wallets;

  public TransferController(EventGateway events, TransferStateReader transfers, WalletService wallets) {
    this.events = events;
    this.transfers = transfers;
    this.wallets = wallets;
  }

  @PostMapping("/transfer/{id}")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TransferStarted start(@PathVariable String id, @RequestBody Transfer transfer) {
    if (id.isBlank() || transfer.amount() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A transfer ID and positive amount are required");
    }
    events
      .publish(List.of(new TransferRequested(id, transfer.from(), transfer.to(), transfer.amount())))
      .join();
    return new TransferStarted(id);
  }

  @GetMapping("/transfer/{id}")
  public TransferState transfer(@PathVariable String id) {
    return transfers.find(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown transfer: " + id));
  }

  @GetMapping("/wallet/{id}")
  public WalletBalance wallet(@PathVariable String id) {
    return new WalletBalance(id, wallets.getBalance(id));
  }
}
