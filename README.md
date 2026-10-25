# Axoniq Workflow Engine transfer demo

Java 21/Spring Boot demo using Axoniq Framework **5.4.1** and Axon Server **2026.1.7**. This is the new Workflow Engine API—not an Axon Saga. `TransferRequested` starts the workflow, and `withdraw` and `deposit` are named `awaitExecute` steps. `GET /transfer/{id}` reads workflow state by ID through `WorkflowManager`; no application projection is required.

## Run

Prerequisites: JDK 21, Maven 3.9+, and Docker with Compose.

```bash
mvn test
docker compose up -d
mvn spring-boot:run
```

The Compose configuration creates the `default` event-store context with DCB enabled, which the workflow engine requires. Axon Server's UI is at <http://localhost:8024>.

The application also stores event processor positions in a local H2 database at `target/axon-tokens.mv.db`. The JDBC starter lets Axon auto-configure its `TokenStore`, and `schema.sql` creates its table on startup. Axon Server stores the events; it does not provide this token store. Keep the H2 database alongside the wallet files when restarting the demo; `mvn clean` removes both.

Axon Server's metadata, events, and replication logs are bind-mounted from `target/axonserver/data`, `target/axonserver/events`, and `target/axonserver/log`. All demo state lives under `target/`. To reset everything, stop the Java application, then run:

```bash
docker compose down
mvn clean
docker compose up -d
mvn spring-boot:run
```

Stop Axon Server before cleaning, so it closes its files before they are removed. Recreate its container after cleaning so Docker mounts the newly created directories. The fixed node name `axon-server` allows container recreation while retaining data.

```bash
curl http://localhost:9000/wallet/a
curl http://localhost:9000/wallet/b
curl -i -X POST http://localhost:9000/transfer/1 \
  -H 'content-type: application/json' \
  -d '{"from":"a","to":"b","amount":10}'
curl http://localhost:9000/transfer/1
```

The final balances are `9990` and `10010`, and the transfer status is `COMPLETED`.

## Kill/restart rehearsal

Transfers with an amount of **200 or more** pause durably for **20 seconds** after withdrawal and before deposit. Smaller transfers proceed immediately; no pause property is needed.

```bash
mvn spring-boot:run
```

Post a transfer with a fresh ID and `"amount":200`, wait for status `WITHDRAW_SUCCEEDED` and the durable `pauseAfterWithdraw` wait, then kill the Maven/Java process with `kill -9`. Restart with the same command. The workflow event stream records completed steps and the wait, so the engine reconstructs the instance and proceeds to `deposit`.

`TransferWorkflowPayload` is the application's typed workflow data, containing the transfer details and business status. `TransferWorkflow` explicitly persists `STARTED`, `WITHDRAW_SUCCEEDED`, `COMPLETED`, or `FAILED` through named payload-update steps. `TransferStateReader` looks up `WorkflowStateQuery.byWorkflowId(id)` using the manager for Spring Boot's `SimpleWorkflowContext` module, deserializes the payload, and returns its business state. It does not infer business status from Axon's lifecycle status or step names. Engine cancellation and other runtime stops are separate from these business milestones. Business progress events are still published for other consumers, but the endpoint does not consume them to maintain a separate status map.

The named payload-update steps change the workflow definition's recorded history. Existing instances from the earlier definition need a workflow-version migration before they can safely resume with this definition.

The manager reads a local live execution when available, or reconstructs state from the event store for an ID absent locally. This removes dependence on a custom projection; it does not establish linearizable reads. A request can return 404 before the workflow has started, even after its trigger event was accepted.

Wallet balances survive Java restarts in `target/wallets/a.json` and `target/wallets/b.json`. Each wallet starts at `10000` only when its file does not exist. The local repository saves each mutation before returning, using a temporary file and atomic replacement. It serializes updates within one application process; this is demo storage, not a multi-process database.

The demo provides persistence only, without idempotency. An external effect can happen before its step-completion event is stored, so a crash in that window leaves its outcome uncertain. Without a retry policy, the engine reports an indeterminate step rather than automatically repeating it. Retrying such effects requires idempotency/deduplication.

Axon Framework's core is Apache-2.0, but Axoniq Workflow Engine is an Axoniq add-on currently documented as preview. Evaluation is allowed under the Axoniq Terms of Service; continued/production use requires registration and an appropriate commercial license. Do not treat this demo as production guidance.
