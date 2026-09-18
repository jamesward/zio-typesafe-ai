# Jev loop architecture

`TypeSafeAI.loop` turns Jev — a System One model that only answers typed,
atomic questions — into the outer decision loop of an application state
machine. Jev never generates an action; on every iteration the host
generates the *entire* finite set of legal actions and Jev answers one
`Question.Choice`: "which of these advances the state?"

That inversion is the whole design. The host owns the state type `S`, the
action type `A`, effects, and termination; Jev owns exactly one decision per
turn, and that decision is a calibrated probability distribution over ids the
host minted itself.

## Components

```mermaid
flowchart TB
    subgraph host["Host application"]
        S["state: S"]
        SV["stateView: S => Content"]
        OPT["options: S => ZIO[R, E, NonEmptyChunk[LoopOption[A]]]"]
        H["handler: (S, A) => ZIO[R, E, LoopStep[S, O]]"]
    end

    subgraph pub["TypeSafeAI (public API)"]
        LR["LoopRequest[S, A, R, E, O]<br/>.model / .maxIterations / .run"]
    end

    subgraph internal["internal (never imported by users)"]
        LI["LoopImpl.run<br/>recursive step/5"]
        DSR["DynamicSystemOneRequest<br/>one question: next_action"]
        RI["RequestImpl.runDynamic"]
        HP["Helpers.toWireRequest<br/>fromWireDynamicAnswer"]
        HTTP["Http.HttpClient.send"]
    end

    JEV["api.typesafe.ai<br/>POST /v1/systemone"]

    S --> LR
    SV --> LR
    OPT --> LR
    H --> LR
    LR -->|run| LI
    LI -->|"stateView(state) + ChoiceCriteria"| DSR
    DSR --> RI
    RI --> HP
    HP --> HTTP
    HTTP -->|"bearer auth, JSON"| JEV
    JEV -->|"Answer.Choice"| HTTP
    HTTP --> RI
    RI -->|"DynamicAnswer.Choice"| LI
    LI -->|"option.value: A"| H
    H -->|"LoopStep.Continue / Done"| LI
    LI -->|"LoopResult[O]"| LR
```

`LoopImpl` reaches Jev through `DynamicSystemOneRequest`, not the typed
`ask` path. It has to: the option set is built at runtime, so there is no
compile-time `NamedTuple` to key the answer by. The loop pays for that with a
runtime map lookup on the fixed id `next_action` and an explicit variant
match on `DynamicAnswer`.

## One iteration

```mermaid
sequenceDiagram
    participant L as LoopImpl.step
    participant O as options(state)
    participant C as ChoiceCriteria
    participant J as Jev
    participant H as handler(state, action)

    L->>O: generate legal actions for this state
    O-->>L: NonEmptyChunk[LoopOption[A]]
    Note over L: reject empty ids<br/>reject duplicate ids
    L->>C: fromContent(id -> description)
    C-->>L: ChoiceCriteria (1-255 options)
    Note over L: stateView(state) -> Content
    L->>L: Clock.nanoTime (start)
    L->>J: POST /v1/systemone<br/>state + next_action Choice
    J-->>L: choice + probabilities + confidence
    L->>L: Clock.nanoTime (stop) -> latencyMs
    Note over L: resolve choice id back to LoopOption[A]<br/>record LoopTurn
    L->>H: (state, option.value)
    H-->>L: LoopStep.Continue(next) | LoopStep.Done(output)
```

Only two things cross the wire: the `Content` view of the state and the
option *descriptions*. `LoopOption` keeps `id`, `value: A`, and
`description: Content` separate precisely so the host's real action value
never has to be serializable — Jev sees the description, the host gets back
the `A`.

Option ids are opaque to Jev by construction. The question text says so
("Option ids are opaque; judge their structured descriptions"), which keeps
a terse id like `finish` from doing semantic work the description should be
doing.

## Control flow and exits

```mermaid
flowchart TD
    START(["run"]) --> MAXCHK{"maxIter > 0?"}
    MAXCHK -->|no| E1["Error.InvalidLoop<br/>maxIterations must be positive"]
    MAXCHK -->|yes| STEP["step(initial, iteration = 1, turns = Nil, tokens = 0)"]

    STEP --> ITER{"iteration > maxIter?"}
    ITER -->|yes| E2["Error.MaxIterations"]
    ITER -->|no| OPTS["options(state)"]

    OPTS -->|"fails with E"| EH["host error E<br/>(untouched)"]
    OPTS --> VALID{"ids non-empty<br/>and unique?"}
    VALID -->|no| E3["Error.InvalidLoop"]
    VALID --> CRIT{"ChoiceCriteria<br/>1-255 options?"}
    CRIT -->|no| E3
    CRIT --> ASK["ask Jev: next_action"]

    ASK -->|"HTTP / decode failure"| E4["Error.BadRequest, RateLimit,<br/>Transport, ..."]
    ASK --> ANS{"answer for next_action?"}
    ANS -->|missing| E5["Error.MissingAnswer"]
    ANS -->|"wrong variant"| E3
    ANS --> RESOLVE{"choice matches<br/>a known option id?"}
    RESOLVE -->|no| E6["Error.InvalidLoop<br/>Jev selected unknown option"]
    RESOLVE --> HANDLE["handler(state, action)"]

    HANDLE -->|"fails with E"| EH
    HANDLE --> STEPKIND{"LoopStep"}
    STEPKIND -->|Continue| ACC["accumulate turn + usage<br/>iteration + 1"]
    ACC --> ITER
    STEPKIND -->|Done| RESULT(["LoopResult[O]"])
```

Every validation gate runs *per iteration*, not once at build time, because
`options` is a function of the current state — a host that returns a
duplicate id only in some late state fails there, before anything is sent.

The error channel is the union `Error | E`, and the environment is
`Client & R`. Host failures pass through unwidened: a loop over a database
never has to wrap its own errors in a library error type, and `E = Nothing`
(as in the counter example) collapses the union back to `Error`.

## What accumulates

```mermaid
flowchart LR
    T1["turn 1<br/>choice, answer,<br/>usage, latencyMs"] --> T2["turn 2"] --> TN["turn n<br/>(Done)"]
    TN --> R["LoopResult"]
    R --> O["output: O"]
    R --> TS["turns: List[LoopTurn]"]
    R --> U["usage: Usage<br/>summed input/output tokens"]
    R --> L["latencyMs: Long<br/>sum of per-turn Jev latency"]
```

`LoopTurn` records the *full* `ChoiceAnswer`, not just the selected id — the
probability distribution over every option and Jev's confidence are kept for
each decision, which is what makes a loop auditable after the fact ("it chose
`escalate`, but `retry` was at 0.46").

Two things the aggregates deliberately do not include:

- **`latencyMs` is Jev time only.** It is measured around the HTTP call and
  summed across turns, so it excludes whatever the handler spent doing real
  work. It answers "how much of this loop was the model?", not "how long did
  this loop take".
- **`usage` is Jev tokens only.** A handler that calls a generative model
  reports its own usage through its own channel.

The turn is recorded *before* the handler runs, so a handler that fails still
leaves its decision in the reconstructed history when the failure is caught
and inspected upstream.

## Why the handler is the escape hatch

`handler` returns `ZIO[R, E, LoopStep[S, O]]`, so an action can do anything a
ZIO can do — an MCP call, a database write, a generative model call with no
tools — while Jev stays the outer loop. This is the opposite of tool-calling:
instead of a generative model emitting a tool name it might hallucinate, a
System One model picks from a set that the host proved legal for this exact
state, and the picked value is already the typed `A` the handler expects.

`Error.InvalidLoop("Jev selected unknown option ...")` is therefore a
protocol violation, not a normal outcome — it can only fire if the model
returns an id outside the criteria it was given.
