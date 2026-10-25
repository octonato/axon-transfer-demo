# Axon application walk-through

An interactive walk-through of this transfer application, starting with no assumed knowledge of Axon. The focus is the architecture of AxonIQ Workflows: how unfinished work is discovered, what runs on the server, how state survives, and how the single-writer principle applies.

## Itinerary

1. The running components: application, workflow engine, and Axon Server.
2. How a transfer request starts a workflow.
3. How withdrawal and deposit become durable steps.
4. How the engine discovers unfinished work after a restart.
5. How ownership and concurrency relate to the single-writer principle.
6. How the application exposes progress, and what this demo actually preserves.

## Step 1: What runs where

Open these locations:

compose.yaml:1  
src/main/resources/application.properties:2  
src/main/java/com/example/transfer/application/TransferWorkflow.java:26

This application transfers money between two pretend wallets. Its process is: withdraw from wallet A, optionally wait, then deposit into wallet B.

There are **two processes** in this setup:

| Component | Responsibility |
|---|---|
| Spring Boot application | Receives HTTP requests, runs the workflow engine, and executes the wallet operations. |
| Axon Server | Stores events durably and supplies those events to the application. |

The **workflow engine is a library running inside your Java application**. The `@Workflow` annotation identifies the Java method that defines the transfer process. Axon describes this model as ordinary imperative code whose steps become durable events. [Axon workflow introduction](https://docs.axoniq.io/axon-workflow-getting-started/development/first-workflow/)

**Yes, this demo requires an extra server: Axon Server.** Docker Compose starts it, and `application.properties:2` tells the Java application to connect to `localhost:8124`. The Docker volumes at `compose.yaml:9` preserve its stored data across container replacement.

**Axon Server does not act as an HTTP proxy here.** Your request goes directly to Spring Boot on port `9000`. The Java application communicates separately with Axon Server to store and consume events. The withdrawal and deposit methods execute inside the Java application.

Keep this division in mind: **the application executes the process; Axon Server preserves the history that lets the engine reconstruct its progress.** We’ll follow that connection through the code next.

### Discussion after Step 1

**User:**

> so the server here is functioning as a storage engine, not an execution runtime like it's the case with Restate, Temporal and Akka

**Assistant:**

Yes—**in this application, Axon Server provides durable event storage, while the workflow execution runtime lives inside the Spring Boot JVM.**

The split is:

- **Axon Server:** persists events and streams them to consumers.
- **Embedded workflow engine:** reconstructs workflow state, determines what comes next, manages waits, and invokes your code.
- **Your application code:** performs the withdrawal and deposit.

If Spring Boot stops, Axon Server retains the history, but **no transfer code executes until an application instance runs again**.

The comparison with other platforms needs care: a separate orchestration server does not necessarily execute business code itself; some platforms execute that code in application workers. The defining point here is that **Axon embeds workflow orchestration in your application too**.

**User:**

> that part about embedding is not so clear now
> Is the workflow engine running inside my application or on the axon server?
> I understand as: it runs in my application and it uses the server as storage

**Assistant:**

Yes, your understanding is correct: **the workflow engine runs inside your application and uses Axon Server as durable storage.**

Your Spring Boot application contains both:

- Your transfer code: “withdraw, wait, deposit.”
- The Axon workflow engine: runs that code, records progress, and reconstructs workflows after a restart.

Axon Server runs separately and stores the events. It does **not** run the workflow engine or your transfer code.

“Embedded” simply means the engine is a Java library loaded into your application’s JVM.

## Step 2: How a request starts a workflow

Open these locations:

src/main/java/com/example/transfer/web/TransferController.java:34  
src/main/java/com/example/transfer/events/TransferRequested.java:3  
src/main/java/com/example/transfer/application/TransferWorkflow.java:26

Suppose you send this request to the Spring Boot application:

```http
POST /transfer/1
Content-Type: application/json

{"from":"a","to":"b","amount":10}
```

The controller checks the ID and amount, then publishes a `TransferRequested` event containing `transferId = "1"`, the two wallet IDs, and the amount. An event is a recorded fact: here, “a transfer was requested.” The controller does not call `TransferWorkflow.transfer()` directly.

With this application's Axon configuration, the event is stored in Axon Server and consumed by the embedded workflow engine. The `@Workflow` annotation supplies the matching rule:

```java
@Workflow(
    workflowName = "MoneyTransfer",
    idProperty = "transferId",
    startOnEventClass = TransferRequested.class,
    workflowNamespace = "com.example.transfer")
```

Read this as: “When a `TransferRequested` event arrives, use its `transferId` to identify an instance of the `MoneyTransfer` workflow.” The workflow definition is the reusable recipe; a workflow instance is one particular transfer following that recipe. Transfers `1` and `2` have separate workflow instances.

The engine supplies a `SimpleWorkflowContext` when it invokes the workflow method. At lines 32–36, the method reads the transfer details from that context's payload, meaning the data associated with this workflow instance. It can then begin the withdrawal step.

The HTTP response is **202 Accepted**. The `.join()` at controller line 40 waits for event publication to complete; it does not wait for withdrawal and deposit to finish. Acceptance and workflow completion are separate milestones.

The path so far is:

```text
HTTP request → controller → stored TransferRequested event
                                     ↓
                         embedded workflow engine
                                     ↓
                         MoneyTransfer instance "1"
```

This answers the first part of “how does it know there is work?”: a matching event triggers the workflow. Discovering an already-started, unfinished workflow after a crash is a separate mechanism, covered in Step 4.

### Discussion after Step 2

**User:**

> I see that there lots of awaits and join. So the first questions: is Axon running on virtual threads? If not, we are calling .join() on the thread that is serving the request

**Assistant:**

**The workflow instances run on virtual threads, but the HTTP request thread is configured separately. Your observation about `.join()` is correct.**

For the workflow engine, I checked the actual 5.4.0 dependency source: `WorkflowConfigurationDefaults.registerWorkflowEngineExecutor()` defaults to `Executors.newVirtualThreadPerTaskExecutor()`. The workflow's `awaitExecute` calls therefore wait on a virtual thread. Waiting can park that virtual thread and release its underlying platform thread. [Axon workflow lifecycle documentation](https://docs.axoniq.io/axon-framework-reference/development/workflows/workflow-lifecycle/)

For the HTTP controller, `events.publish(...).join()` runs directly on the thread serving the request. This project does not set `spring.threads.virtual.enabled=true`, so with its default Spring Boot/Tomcat configuration, that is a platform thread. It remains occupied until event publication finishes, including the event-store round trip. It does not wait for the workflow to complete.

| Call | Thread in this demo | What it waits for |
|---|---|---|
| Controller `.join()` | HTTP platform thread, under the checked-in defaults | Event publication |
| Workflow `awaitExecute(...)` | Workflow virtual thread | Completion of that workflow step |

Enabling `spring.threads.virtual.enabled=true` would let Spring Boot use virtual threads for HTTP request handling too. That would make the controller's blocking wait cheaper; it would still be a wait. Alternatively, the controller could return an asynchronous result instead of calling `.join()`. [Spring Boot virtual-thread support](https://spring.io/blog/2023/10/16/runtime-efficiency-with-spring/)

No application configuration or code was changed. We remain on Step 2.

**User:**

> you can enable it for now, but smells like a flaw.
> Most users will want to know wait the confirmation that the event is on the queue. Therefore, the EventGateway should be running on VT.

**Assistant:**

Enabled `spring.threads.virtual.enabled=true` in `src/main/resources/application.properties`. Under Spring Boot's default Tomcat configuration, HTTP requests now run on virtual threads, so the controller's `.join()` waits on a virtual thread too.

Waiting for confirmation that the event was durably stored is a reasonable API requirement. In this application the confirmation concerns event-store publication, rather than merely placing a job on an in-memory queue.

The distinction is where the wait happens: `EventGateway.publish(...)` already returns a future. The controller chooses to wait by calling `.join()` on that future. Even if gateway internals used virtual threads, `.join()` would still wait on the controller's calling thread. The options are to make that caller a virtual thread (now configured) or have the controller return an asynchronous result that completes after publication. Both can preserve the requirement to acknowledge only after publication succeeds.

The blocking controller is therefore a threading choice in this demo; it does not by itself establish a flaw in the gateway. No application runtime was started to verify the new configuration.

**User:**

> yes, correct. Blocking here is still on the serving thread

**Assistant:**

Exactly. `.join()` still blocks the serving thread. With virtual threads enabled, that serving thread is a virtual thread: it can park during the wait, freeing its underlying platform thread. The HTTP response still waits for publication confirmation.

**User:**

> on a different topic, before we move one, I want to make a parellel with Akka
> Axon seems to have a similar approach. In akka you send message to a mailbox. The mailbox has an address (the actor's address)
> In axon, they seem to use a generic queue. Evertyhing goes in there and then dispatched to the right target.
> They probably loop through the list of known targets untill they see someone expected to receive that message.
>
> We don't need to dig deep into this now. I just want to know if I'm understanding it in the right direction

**Assistant:**

Yes, broadly: you are recognizing the difference between addressing a recipient and publishing an event that interested consumers react to.

For this application's event path:

- With the actor-mailbox model you described: “Send this message to actor X.”
- With Axon events here: “Record that transfer 1 was requested.” The workflow engine and the transfer projection can both react to that same event.

Think of Axon Server as a durable event log rather than a queue where one recipient takes and removes each message. Different consumers can read the same events and track their own progress.

The workflow engine matches the event against registered workflow triggers and uses `transferId` to identify the instance. That is the right direction for your mental model. Avoid assuming it must scan every target until it finds one: matching and routing are implementation details, and an event can interest multiple consumers. [Workflow trigger configuration](https://docs.axoniq.io/axon-framework-reference/5.4/workflows/configuration/)

This comparison describes the event path we are following, rather than every messaging mechanism in Axon. We remain on Step 2.

**User:**

> yep, I get this is can be compared to Akka's journal + Akka Projections. Each consume tracks where it is and you can have more than one consumer reading the same journal and consuming the same events.
>
> We can move to the next step

**Assistant:**

Yes, the journal-and-projections comparison fits this event flow.

## Step 3: How operations become durable steps

Open these locations:

src/main/java/com/example/transfer/application/TransferWorkflow.java:38  
src/main/java/com/example/transfer/application/WalletService.java:16  
src/main/java/com/example/transfer/application/WalletRepository.java:59

The workflow's first operation is:

```java
context.awaitExecute("withdraw", Integer.class,
    () -> wallets.withdraw(from, amount));
```

The lambda is ordinary Java code that changes a wallet balance. The surrounding `awaitExecute` gives that operation a stable step name, `withdraw`, and lets the engine record its lifecycle and result. For a successful first execution, the conceptual sequence is:

```text
Persist WithdrawStarted
    → execute wallets.withdraw(...)
    → persist WithdrawCompleted with its integer result
    → continue the workflow
```

The returned integer is the updated balance. Although this workflow ignores that return value, the engine records it. On recovery, a completed step supplies its recorded result instead of executing the wallet operation again. This is why the workflow wraps the operation in a context call instead of simply calling `wallets.withdraw(...)`. [Execute steps](https://docs.axoniq.io/axon-framework-reference/5.4/workflows/execute-steps/), [Recovery overview](https://docs.axoniq.io/axon-workflow-getting-started/development/first-workflow/)

Next, `awaitPublish("recordWithdrawal", new TransferWithdrawn(...))` publishes the business fact that withdrawal succeeded. These are two different records: the engine's step-completion event supports execution recovery, while `TransferWithdrawn` feeds application consumers such as the transfer projection. Publishing is itself a durable step: when replay finds it completed, the engine does not publish it again. [Publishing events](https://docs.axoniq.io/axon-framework-reference/5.4/workflows/publishing-events/)

The remaining flow is a named 20-second sleep for amounts >= 200, then a durable `deposit` action, then publication of `TransferCompleted`. Normal return from the method completes the workflow. The sleep is an engine-managed durable wait, rather than a plain `Thread.sleep`; we will follow its recovery in Step 4.

There is a boundary to the guarantee: changing the wallet and persisting the step-completion event are separate operations. If the JVM dies between them, the engine has no completion record proving whether the wallet changed. A completed step can be skipped safely; an interrupted step can have an uncertain outcome. Production effects therefore need idempotency or reconciliation. Here, that could mean remembering an operation key such as `(transferId, "withdraw")` in the same transaction as the balance change, so repeating the operation does not deduct twice.

Current-code note: the workspace has changed since Step 1's initial inspection. `WalletService` now delegates to `WalletRepository`, which writes JSON files under `target/wallets` by default. The earlier description of an in-memory wallet applies to the earlier code. The current wallet persists separately from Axon, and does not yet deduplicate operations by transfer ID. We will use the current code in the remaining steps.

## Step 4: How restart recovery finds unfinished work

Open these locations:

src/main/java/com/example/transfer/application/TransferWorkflow.java:38  
src/main/java/com/example/transfer/application/TransferWorkflow.java:42  
src/main/java/com/example/transfer/application/TransferWorkflow.java:45

There is no recovery loop in our application code: the embedded engine supplies it. In the installed 5.4.0 dependency, `WorkflowEngine.restoreWorkflowsFor(...)` calls `loadRunningWorkflows(...)`, which loads the event-sourced set of running workflow IDs, selects the IDs owned by this processor segment, and restores each instance's durable state. A segment is a partition of workflow ownership; Step 5 explains that ownership.

This answers “how does it know there is a job to finish?”: it reconstructs which workflows started without reaching a terminal state, and what each one has already done. It also catches up with the event stream before activating restored workflow bodies, so stale consumer progress does not cause it to ignore more recent recorded outcomes.

For our example, imagine the withdrawal and its progress publication finished, and the JVM died during the 20-second sleep for an amount >= 200. The stored history conceptually says:

```text
Transfer workflow started
withdraw completed, result = 9990
recordWithdrawal published
pauseAfterWithdraw started, deadline = 12:00:20
[no terminal workflow event]
```

On restart, the engine reconstructs the workflow state and invokes its method again. It does not restore a serialized JVM stack or jump directly to a saved source-code line. The context calls consult the reconstructed history: the completed withdrawal returns its recorded result without deducting again, the completed publication is not repeated, and the outstanding sleep uses its recorded deadline. This replay model is why side effects belong inside durable context steps rather than unguarded workflow code. [Workflow recovery overview](https://docs.axoniq.io/axon-workflow-getting-started/development/first-workflow/)

If the app restarts at 12:00:10, it waits approximately 10 more seconds. If it restarts at 12:01:00, the deadline has passed, so the wait can finish immediately and execution can proceed to `deposit`. It does not begin a fresh 20-second delay. Sleep is implemented as a durable wait that ends by timeout. [Durable sleep](https://docs.axoniq.io/axon-framework-reference/5.4/workflows/waiting-for-events/)

The timer runs inside the application too. The dependency's `DefaultWorkflowScheduler.schedule(Instant deadline)` schedules the remaining delay using the application's clock; overdue deadlines get zero delay. When all application instances are stopped, no timer executes business code. The stored deadline remains available for recovery.

Two kinds of durable progress have different jobs: the consumer checkpoint says where processing has reached in the event stream; the workflow history says which steps a particular transfer completed and what it is waiting for. Recovery needs both concerns. The example assumes withdrawal completion was durably recorded; a crash inside the action/completion gap remains uncertain, as discussed in Step 3.

### Discussion after Step 4

**User:**

> I think your comment about the journal, consumer and execution history a bit off
> One thing is the event gateway. You publish events, others consume and keep track of what they saw already.
>
> It happens that a workflow starts by consuming one of those events, but could be differnt. That's a choice they made.
> Now, if we forget how an workflow starts, we have the worklow as another producer of events. Its own execution events.
> Those events are used to replay the execution back to the point where it 'died'.
>
> And on top of that, you need something to track which Workflows didn't complete. So inside the Axon server, you need to keep track of started-but-not-finished flows.
>
> Comment if needed, and then move to next step

**Assistant:**

Your separation is clearer. My previous explanation mixed event consumption with workflow recovery. The trigger event starts this workflow; the engine's execution events reconstruct its progress; a running-workflows view identifies which instances need restoring.

One placement detail: Axon Server stores the lifecycle events, but the embedded engine reconstructs the unfinished-workflow set. In the installed source, `EventSourcedRunningWorkflows` selects events tagged `workflowEvent=lifecycle`, adds IDs on `STARTED`, and removes them on `COMPLETED`, `FAILED`, `TIMED_OUT`, or `CANCELLED`. Thus no separate workflow-aware scheduler inside Axon Server is required. “Unfinished” excludes terminal failures, not just successful completions.

## Step 5: Ownership and the single-writer principle

Open these locations:

src/main/resources/application.properties:6  
src/main/java/com/example/transfer/application/TransferWorkflow.java:28  
src/main/java/com/example/transfer/application/WalletRepository.java:59

The intended model is one active owner per workflow instance. A workflow ID maps deterministically to a processor segment. Application nodes claim segments through the processor's token store, and the owner runs the workflows assigned to its claimed segments. If a node fails, another can claim its segments and restore those workflows. Segment ownership groups workflow instances; it is not one thread per workflow.

Claims alone are not sufficient when an old owner loses its claim without noticing. Workflow event appends also carry a condition scoped to the workflow ID and the history position observed by that execution. If another writer has advanced that workflow history, a stale append is rejected and the engine stops that execution. I verified this in `SimpleWorkflowExecution.appendWorkflowEvent(...)` and `appendConditionFor(...)` in the installed 5.4.0 source. This protects the workflow history; it does not undo an external effect already performed by an old owner.

This demo configures one initial segment and a processor claim capacity of one through `axon.workflow.thread-count=1`. That property does not mean only one workflow virtual thread exists: multiple workflow instances can be active or waiting.

The demo has no shared durable token-store configuration. Axon's documented fallback is an in-memory token store, whose claims coordinate only within one process. Therefore this demo should run as one application instance; it is not configured for distributed ownership and failover merely by connecting several instances to the same Axon Server. Multiple nodes need a shared durable token store, such as a configured `JpaTokenStore`. [Multi-node workflow configuration and failover](https://docs.axoniq.io/axon-framework-reference/5.4/workflows/configuration/)

There is also a separate concurrency boundary around wallet balances. Two different transfers can target wallet A even though each transfer has a unique workflow owner. `WalletRepository.updateBalance(...)` uses `synchronized`, which serializes updates within that repository object in one JVM. It provides no cross-process wallet locking. Workflow ownership is per transfer, not per wallet; shared wallet storage would need its own transaction/concurrency controls and idempotency.

### Discussion after Step 5: failover, optimistic concurrency, and stale owners

**User:**

> Write protection: so, some sort of optimistic locking, right?
>
> About failover, if a new node claims the segment and an old still alive, both have the change to write, the second will fail due to optimistic locking.
> I can't see how the old will be kick out automatically. As long as the new haven't written anything, the old will be able to write.
> When the versions differ, then they will start to fail.
>
> But they can keep failing on optimistic locking over and over. As long as the old is not removed, this situation can happens.
> Please, research this and give me a ground analysis

**Assistant — research grounded in the locally installed 5.4.0 sources and official documentation:**

Yes: the workflow append protection is optimistic concurrency control. Your race is possible. My earlier explanation overstated the association between the new segment owner and the winner of the append race.

There are two independent mechanisms:

1. A segment claim is lease-like ownership in the shared token store. Another node can take it after it expires; it does not remotely kill the old JVM. For example, `JpaTokenStore.extendClaim()` updates a token only if its owner matches the current node ID. After another node takes the claim, the former owner's renewal fails with `UnableToClaimTokenException`. `WorkPackage` aborts on processing/upkeep failure, and `Coordinator` handles removal and release. The workflow release listener calls `WorkflowEngine.releaseWorkflowsFor()`, which requests workflow-driver shutdown and removes the instances locally. This is cooperative, asynchronous cleanup, not instantaneous exclusion from the event store. [Streaming processor claims](https://docs.axoniq.io/axon-framework-reference/5.4/events/event-processors/streaming/)

2. The event-store append condition checks workflow history, not the current token-store owner or a lease generation. `SimpleWorkflowExecution.appendConditionFor()` uses the workflow's event criteria and a consistency marker. `WorkflowEventPublisher.publish()` applies that condition in a separate event-store unit of work. Thus merely claiming a segment does not fence all future appends from the former owner.

An illustrative race (inference from those source paths, not a fault-injection experiment):

```text
A last observed workflow history at P
A's claim expires; B claims the segment
B restores this workflow at P
A appends using P → accepted, producing Q
B appends using P → rejected because the history advanced
```

The obsolete segment owner can win that race. If B appends first, A instead loses. The marker is not necessarily an integer version; P and Q represent observed event-store positions scoped by workflow criteria.

The missing detail in an endless-retry interpretation is the rejection policy. `SimpleWorkflowExecution.appendWorkflowEvent()` sets `StopReason.APPEND_REJECTED` and interrupts the workflow driver. The driver's `finally` calls `finishWorkflow()`, whose termination callback calls `WorkflowEngine.removeExecution()`. That removes the local execution. It does not refresh the marker and retry the body forever, nor does it record a business-level terminal failure. Already queued/in-flight work can still produce additional failures during cleanup. A new restoration can happen later on restart or a fresh segment claim. [Documented append-rejection handling](https://docs.axoniq.io/axon-framework-reference/5.4/workflows/configuration/)

This leaves a liveness concern: if B is the legitimate owner but loses the append race and drops its instance, then A also stops after detecting claim loss, the workflow can remain non-terminal with no active driver. In the inspected source I found restoration on startup/segment claim, not an immediate periodic reconciliation loop that restores a rejected execution while the segment remains claimed. This is a source-based possible stalled-workflow scenario; it has not been reproduced in a two-node experiment. [Recovery triggers](https://docs.axoniq.io/axon-framework-reference/5.4/workflows/error-handling/)

Another cleanup caveat in 5.4.0: `Coordinator.abortWorkPackage()` calls the final progress flush before notifying the workflow release listener. Those are chained with `thenCompose`; if the flush fails, the notification is skipped and the failure is logged. Therefore it is too strong to claim that every failed renewal necessarily runs workflow cleanup successfully. Append rejection remains a separate stop path. This observation follows from the source ordering; its full runtime consequences were not experimentally verified.

Neither mechanism prevents an already-running wallet action from affecting external state. The engine gates new execute attempts with durable start/retry records, but effects already in flight require their own idempotency or externally enforced fencing. No such fencing is implemented by this demo's wallet repository. [Execution semantics](https://docs.axoniq.io/axon-framework-reference/5.4/workflows/understanding-steps/)

Corrected characterization: lease-based assignment plus optimistic protection of workflow history, with cooperative stopping of stale/rejected executions. This does not establish instantaneous exclusive execution of arbitrary external effects. A rejected execution does not simply retry forever, but the old owner is not guaranteed to be the loser, and progress after the race deserves a failover test.

Source locations inspected (inside the 5.4.0 source JARs in the local Maven repository):

| Class and lines | Evidence |
|---|---|
| `JpaTokenStore.java:260` | Claim renewal checks token owner and fails if no row matches. |
| `WorkPackage.java:459`, `:537` | Processing failures abort; idle upkeep renews claims. |
| `Coordinator.java:1383`, `:1509` | Aborted packages are removed; final flush precedes release notification. |
| `WorkflowSegmentChangeListener.java:86` | Segment release calls the workflow engine's release method. |
| `WorkflowEngine.java:401`, `:618`, `:654` | Release requests stop/removal; execution termination removes local state. |
| `SimpleWorkflowExecution.java:249`, `:620`, `:646` | Rejection stops/removes the execution; append condition uses workflow criteria and marker. |
| `SequencedAppender.java:57`, `:80` | Successful appends advance the local marker; failed appends are not automatically reloaded/retried. |
| `WorkflowEventPublisher.java:59` | Workflow append applies its condition in a child unit of work. |

No application code was changed and no distributed failover experiment was run. We remain on Step 5.

## Step 6: Reading progress and separating the persisted state

Open these locations:

src/main/java/com/example/transfer/application/TransferProjection.java:17  
src/main/java/com/example/transfer/web/TransferController.java:45  
src/main/java/com/example/transfer/application/WalletRepository.java:59  
src/main/resources/application.properties:8

The transfer status returned to clients is a separate read model. `TransferProjection` consumes three business events and updates an in-memory map:

| Business event | Status shown to clients |
|---|---|
| `TransferRequested` | `STARTED` |
| `TransferWithdrawn` | `WITHDRAW_SUCCEEDED` |
| `TransferCompleted` | `COMPLETED` |

`GET /transfer/{id}` reads this map. It does not ask a running workflow thread where it is, nor reconstruct workflow execution history for each HTTP request. That is the CQRS split here: execution produces events, while a separate projection builds the representation used for reads.

Consequently, accepting a request and showing its progress are separate milestones. The projection may briefly lag behind publication; immediately after a successful POST, a GET could still return 404 until the request event is projected. This projection has no handlers for engine failure events, so a failed workflow can leave the public transfer status at its last business milestone. For example, a deposit failure can leave `WITHDRAW_SUCCEEDED` visible.

There are three distinct kinds of application state:

| State | Storage | Recovery |
|---|---|---|
| Workflow progress, results, waits, and lifecycle | Events in Axon Server | Embedded engine reconstructs workflow state and unfinished IDs |
| Public transfer status | In-memory projection map | Can be rebuilt by replaying business events |
| Wallet balances | JSON files under `target/wallets` by default | Wallet repository reads existing files on restart |

The consumer checkpoint/claim store is additional processing infrastructure, not a fourth business-state model. This demo uses the in-memory fallback discussed in Step 5. If the projection were given durable checkpoints while its data stayed in memory, restart could resume consumption after events whose effects had been lost. A durable checkpoint alone cannot preserve an in-memory read model; persist that read model consistently or deliberately rebuild it.

Axon Server does not automatically persist arbitrary Java fields or wallet files. The workflow engine persists the information needed to reconstruct workflow execution through its primitives. Our wallet persistence is application code with separate consistency requirements.

### Recap

1. Spring Boot contains both the workflow definition and the embedded execution engine; Axon Server stores the events.
2. The HTTP endpoint publishes `TransferRequested`, which identifies and starts a transfer workflow instance.
3. Durable context calls record step progress/results and publish business milestones. HTTP request handling and workflow execution use separately configured virtual threads.
4. Lifecycle events reconstruct the unfinished-workflow set; execution events reconstruct each instance, including outstanding deadlines.
5. Segment claims assign instances to nodes, and conditional appends protect workflow history. Our failover research identified the distinction between optimistic history protection and immediate ownership fencing, plus possible progress/cleanup issues requiring a distributed test. External effects still need their own correctness controls.
6. A separate projection serves transfer status, while wallet files preserve balances independently.

The engine supplies durable orchestration around ordinary Java code. It does not automatically make the withdrawal, deposit, event history, and read model one atomic transaction. An interrupted execute attempt is not automatically rerun without a retry policy; it can instead have an indeterminate outcome. The earlier README's broad wording that a crash may repeat an effect should be read with that execution-semantics qualification.

Walk-through complete. Follow-up questions can continue under the relevant step.

### Discussion after Step 6: direct workflow state access and consistency

**User:**

> So, we read the state of the workflow we are depending on a projecion (CQRS style).
>
> Do they provide any means to read the state directly from a workflow?
> Could a workflow have a state?
>
> I see a major architectural limitation.
> Workflows are not addressable. You publish messages to a gateway and they land on the workflow.
> If they are not addressable, you can't reach out to them.
>
> I would like to confirm this assumption with grounded information. That's crucial for my demo comparions. I will ultimately compare Restate, Temporal Axon and Akka. All frameworks but Axon support accessing state.
>
> To summarize, all reads seem to depend on projections and therefore are eventually consistent.
> While in Akka, for example, reads by ID are strictly consistent (no CQRS fundamentatims here) because we hit the entity/workflow directly

**Assistant — research grounded in the installed Axon 5.4.0 sources and official documentation:**

That conclusion is too broad. Axon has durable workflow state and an API for reading it by ID, without our custom `TransferProjection`. Step 6 described this demo's endpoint implementation; it should not be interpreted as a platform requirement.

The public API is:

```java
workflowManager
    .findOne(WorkflowStateQuery.byWorkflowId(transferId))
    .singleState();
```

It resolves a detached `WorkflowState`, or null when no matching instance exists. The state exposes the payload, lifecycle status, and step information. Inside the workflow, `ctx.workflowPayload()` reads its durable data, and named `ctx.setPayload(...)` calls record changes. Ordinary Java local variables are not automatically persistent state. [Workflow Manager](https://docs.axoniq.io/axon-framework-reference/5.4/workflows/workflow-manager/), [Payload and State](https://docs.axoniq.io/axon-framework-reference/5.4/workflows/payload-and-state/)

In the installed `SimpleWorkflowManager.matching(...)` implementation, a matching local execution supplies its current in-memory state. Otherwise, an explicit workflow ID is included as a candidate regardless of whether the history projection has discovered it, and its state is reconstructed through `workflowStore.findWorkflow(...)`. The returned state is copied by `DetachedWorkflowState`. Thus a by-ID lookup is not forced to wait for our transfer projection or for discovery by the manager's history projection. Broad searches without an explicit ID still depend on locally known candidates.

There is, however, a distinction between lookup by ID and an owner-routed command/query API. The default manager implementation searches local repositories and sources other IDs from storage; it does not forward a custom query handler to the node owning the workflow. There is no workflow-driver query task or catch-up barrier in this manager read path. Local live executions evolve their state from events queued to the driver (`SimpleWorkflowExecution.onEvent(...)`), so reading that state is not itself proof that every already-committed event has been applied.

Therefore the evidence supports three separate conclusions:

- Durable workflow state: supported.
- State lookup by workflow ID without a custom projection: supported.
- A general linearizable custom query routed to the owning workflow and serialized with its processing: not provided by the inspected default Workflow Manager implementation.

I did not find a documented linearizability guarantee for Workflow Manager reads. It is also inaccurate to conclude that every possible Axon state read is eventually consistent: the manager's durable-store path is different from a lagging projection, and its guarantees need to be evaluated as a storage read. The narrower concern is that the default API does not demonstrate the same owner-serialized read contract as Akka's component calls.

Akka SDK documents routing by component ID to its owner, one-at-a-time message handling, and workflow handlers that return current state. That supports the user's comparison for reads served by the authoritative instance. Its multi-region read replicas can lag; the documentation explains using an `Effect` handler to route a read to the primary when latest-state consistency is required. Merely specifying an ID does not make every multi-region read strict. [Akka workflow access and state reads](https://doc.akka.io/sdk/workflows.html)

Suggested comparison wording: “Axon supports durable workflow state and by-ID inspection through Workflow Manager. Its default inspection path differs from Akka's owner-routed workflow handlers; the inspected implementation does not establish the same linearizable query contract. The custom CQRS projection in this demo is an application choice.”

Source evidence in local 5.4.0 dependency sources:

| Location | Evidence |
|---|---|
| `SimpleWorkflowManager.java:139` | Local live executions take precedence; explicit IDs are added as durable-store candidates independently of projection discovery. |
| `SimpleWorkflowManager.java:173` | Missing-local-instance lookup reads through `WorkflowStore` in a unit of work. |
| `SimpleWorkflowManager.java:216` | Single-state lookup returns a detached copy. |
| `DetachedWorkflowState.java:53` | Structural copy of status, payload, steps, and version metadata. |
| `SimpleWorkflowExecution.java:527` | Live state evolution is queued to the workflow driver; manager reads do not insert a query into that queue. |

No application code was changed and no distributed consistency experiment was run. This research corrects any interpretation of Step 6 as requiring CQRS for all workflow state reads.

### Sample refactoring: read workflow state by ID

**User:**

> now that we are here, I think we should change the sample to access the state by id instead of depending on TransferProjection

**Assistant:**

Changed `GET /transfer/{id}` to use `TransferQueries`, which resolves the module's `WorkflowManager` and calls `findOne(WorkflowStateQuery.byWorkflowId(id)).singleState()`. The response is built from the workflow payload, lifecycle status, and withdrawal step. Removed `TransferProjection` and its test; business progress events remain available to other consumers.

The endpoint keeps its existing response shape and 404 behavior for missing workflows. It also exposes `FAILED`, `CANCELLED`, and `TIMED_OUT` lifecycle outcomes. Updated the README to describe direct state lookup and its consistency limits.

Replaced the projection test with workflow-engine tests covering an unknown ID, live progress before/after withdrawal, and a completed instance read from the event store after clearing the history projection. The Maven suite passed: 12 tests, no failures or errors. Application startup and HTTP behavior were not verified; the user will run the sample later.

### What “query” means in the state lookup

**User:**

> so, explain me what queries are.
> As I see it, you can't read the state directly on the workflow like Akka would have done
> instead, you reconstruct it using a query that is a ad-hoc replay just to serve the state for a read

**Assistant:**

That describes the fallback path, but not every read. `WorkflowStateQuery.byWorkflowId(id)` is a selection criterion: “find the workflow with this ID.” It is not a request delivered to a query handler inside that workflow, and it does not itself prescribe replay. `TransferQueries` is just our application's class name; it uses Workflow Manager, not Axon's separate Query Gateway API.

The manager follows two paths:

| Situation | Read behavior |
|---|---|
| Workflow is present in this node's live repository | Copy its already-maintained in-memory state. No read-time replay is required. |
| Workflow is absent from this node's live repository | Reconstruct its durable state through the event-store repository. This is event sourcing for the read, rather than invoking its workflow body. |

Thus the second path matches the user's intuition about an on-demand reconstruction. It applies to completed instances and can also apply when another node owns an active instance. The repository may optimize reconstruction; this does not establish that every read must scan all events from scratch.

The difference from an Akka owner-routed handler is the execution contract: Akka routes the call to the authoritative component's handler; this Axon manager either inspects local engine state or reconstructs stored state. It does not dispatch a custom read handler into the workflow's serialized execution. Reconstructing state applies recorded events; it does not rerun withdrawal, deposit, or other workflow actions.

**User:**

> so, don't call it query to avoid confusion with other existing abstractions

**Assistant:**

Renamed the application class and its test to `TransferStateReader` and `TransferStateReaderTest`, and updated the controller and README. Earlier references to `TransferQueries` in these notes describe the previous name. Axon's own API type remains `WorkflowStateQuery`; in explanations we will call this operation a workflow-state lookup or read.

### Typed business payload

**User:**

> strange how that works
> we don't even have a reference to the TransferWorkflow in query
> what you are returning, is the state of the workflow as a Axon primitive
> not the state of the application TransferWorkflow

**Assistant:**

The reader was inspecting Axon's generic `WorkflowState` and deriving application state from payload field names, the `withdraw` step, and the engine lifecycle. It did not call a handler on `TransferWorkflow` or read typed state owned by that application class. That coupled the reader to execution details without compiler checks.

**User:**

> I would prefer to have a custom payload
> this stringly-typed resconstruction is pure slop

**Assistant:**

Added the application record `TransferWorkflowPayload`, holding typed transfer details and `TransferStatus`. Its fields match the trigger event, with a missing initial status defaulting to `STARTED`. The workflow deserializes the context payload into this record and explicitly persists business status changes through named payload-update steps. A failed execute step records the business status `FAILED` before propagating the failure to the engine.

`TransferStateReader` now deserializes that record and returns its typed business state. It no longer casts individual map values or inspects engine step names/lifecycle status. Axon's underlying payload API remains a map; conversion is confined to the serialization boundary. Engine cancellation/runtime status remains separate from business milestones.

Updated tests and README. All 13 Maven tests passed, including live state reads, completed-state reconstruction without the history projection, and a business failure. The application was not started. The added durable payload steps change recorded execution history; existing in-flight instances from the previous definition require migration before resuming under this definition.
