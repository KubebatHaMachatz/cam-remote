# Packages and their responsibilities

What each package is *for*, what it may depend on, and what must never go in it.

[ARCHITECTURE.md](ARCHITECTURE.md) lists what each package contains. This says what each is
responsible for — which is the question you need answered when deciding where a new piece of code
belongs, and the one that keeps the layering from eroding a change at a time.

---

# Android

Two Gradle modules. The dependency arrow points one way only: `:app` depends on `:core`, and `:core`
depends on nothing of Android's. That is enforced by the build graph, not by discipline — `:core`
has no Android on its classpath, so a stray `import android.*` fails to compile.

## `:core` — 2,096 lines, no Android

### `protocol` — 5 files, 449 lines

**Role:** the contract between the two halves of the project. The shape of a request, the shape of a
reply, the vocabulary of errors, and the one place JSON is configured.

**May depend on:** nothing but kotlinx.serialization and the JDK.

**Belongs here:** anything both sides must agree on. `CommandRequest`, `CommandResponse`,
`CommandDescriptor`, `ErrorCode`, `PermissionStatus`, `Params`.

**Does not belong here:** any behaviour beyond parsing and validating a value. `Params` throws on a
malformed parameter, and that is the limit of what this package decides.

The Python client's `models.py` mirrors these types by hand. Changing anything here is the one kind
of change that obliges a matching edit on the other side.

### `port` — 8 files, 297 lines

**Role:** the interfaces `:core` needs the outside world to implement. This package is the entire
reason the core can be Android-free — every platform capability is named here as a method and
supplied from `:app` at runtime.

**May depend on:** `protocol`, and the JDK.

**Belongs here:** an interface, its data types, and nothing else. If a port file grows a decision, it
has grown something that belonged in `logic`.

**Does not belong here:** implementations. There is exactly one exception, `CommandLog.None`, which
is a no-op default rather than an implementation of anything.

Each port has a fake in tests, which is what makes the whole decision layer testable in
milliseconds.

### `logic` — 9 files, 504 lines

**Role:** decisions that need no device. Where a photo may be written, which camera app to prefer,
whether a property name is safe, what a file should be called, how to rank a device's addresses.

**May depend on:** `protocol`, `port`, and the JDK.

**Belongs here:** anything you could describe as a rule. The test for whether code belongs here is
whether you can check it without a phone — if you can, it should be here rather than in an adapter.

**Does not belong here:** I/O. `PhotoIndex` is the instructive edge case: it reads and writes a
file, but a `java.io.File` on a desktop JVM is a real file, so it stays. `MediaStorePhotoStore`
needs a `ContentResolver` and cannot.

This package is where the project keeps its judgement, and it is the first place to look when
asking "why did it choose *that*".

### `command` — 4 files, 267 lines

**Role:** the machinery every capability runs inside. The `Command` interface, the registry that
holds them, the dispatcher that runs them, and the locks that stop two commands touching the same
hardware at once.

**May depend on:** `protocol`, `port`, `logic`.

**Belongs here:** anything true of *every* command — lookup, exclusivity, timeouts, error mapping,
logging. `CommandDispatcher` is the single entrance every transport goes through, which is what lets
a new transport be written without re-deciding any of it.

**Does not belong here:** anything true of only one command.

### `command.impl` — 6 files, 579 lines

**Role:** the capabilities themselves, one file each. This is the package that answers "what can the
agent do".

**May depend on:** everything else in `:core`.

**Belongs here:** one command, doing one thing, expressed against ports rather than against Android.
A command orchestrates: it checks preconditions, asks `logic` for decisions, calls ports, and
returns a `CommandOutcome`.

**Does not belong here:** how the device actually does it. If a file here mentions a platform
detail, that detail has escaped its adapter.

**Adding a capability means adding a file here and one line in `AppContainer`.** Nothing else.

### `testing` (testFixtures) — 1 file, 32 lines

**Role:** doubles shared between the two modules' test suites — `FakeClock` and `TestCommand`.
Published to `:app` so its transport tests drive real commands rather than re-inventing fakes.

## `:app` — 1,544 lines, everything Android

### `adapter` — 9 files, 650 lines

**Role:** one implementation per port. This is where Android lives.

**May depend on:** `:core`, and the whole Android SDK.

**Belongs here:** platform mechanics and nothing else — CameraX bindings, MediaStore inserts,
`Intent` construction, `getprop` execution, logcat writes. Adapters are thin because the decisions
were made upstream.

**Does not belong here:** anything you could have tested without a device. If you find yourself
wanting to unit-test an adapter, the part you want to test probably belongs in `logic`.

### `transport.http` — 2 files, 200 lines

**Role:** get a request from the network into the dispatcher, and the reply back out. The Ktor
routes and the engine's lifetime.

**May depend on:** `:core`, Ktor.

**Belongs here:** the three routes, and the mapping from a typed failure to an HTTP status code.
That mapping is the *only* policy this package holds.

**Does not belong here:** whether a command may run, how long it may take, what it returns. All of
that is the dispatcher's, deliberately, so a second transport inherits it.

### `service` — 2 files, 351 lines

**Role:** the agent's lifetime. `RemoteControlService` owns the HTTP server, the Wi-Fi lock, the
`LifecycleOwner` CameraX binds to, and the notification — which is the whole of the agent's
interface to a human. `BootReceiver` brings it back after a reboot.

**May depend on:** everything.

**Belongs here:** anything about *when* the agent runs rather than *what* it does — foreground
service types, locks, the notification, restart behaviour.

This is the package that replaces what a ViewModel would do in an app with a screen: it is the
lifecycle owner and the scope everything else hangs off.

### `setup` — 1 file, 152 lines

**Role:** hosting the native permission dialogs. `LaunchActivity` draws nothing of its own; it
exists because Android will not let a background service request a permission without *some*
Activity.

**Belongs here:** the sequence of what to ask for next, and nothing more.

**Does not belong here:** any UI. The day this package draws something is the day the project has
acquired the control screen the assignment rules out.

### `di` — 1 file, 150 lines

**Role:** the composition root. One readable file that wires every port to its adapter and lists
every command. Manual constructor injection, no Hilt, no annotation processing.

**Belongs here:** construction and wiring.

**Does not belong here:** behaviour. If `AppContainer` starts making decisions, they belong in
`logic`.

### `config` — 1 file, 41 lines

**Role:** the two pieces of state that survive a restart — the port, and whether the agent is meant
to be running. Backed by private `SharedPreferences`.

---

# Python

One package, three levels, mirroring the agent's layering on purpose: a transport, a protocol, and a
registry of commands where adding one is a file plus a line.

## `camremote` (root) — 6 files, 401 lines

**Role:** the client itself — the CLI, the protocol conversation, and the shared types.

- **`cli.py`** — parses arguments, resolves the address, runs the verb, maps exceptions to exit
  codes. It builds its parser *from* the command registry rather than declaring subcommands by hand.
- **`client.py`** — `RemoteClient`: envelopes out, typed results back, and downloading a photograph.
  The only file that knows the protocol's shape.
- **`models.py`** — mirrors of the agent's wire types. Compare with `core/protocol/Envelope.kt`.
- **`errors.py`** — three exceptions, and the distinction between them is the error model:
  unreachable, refused, or a client-side problem.
- **`__main__.py`** — `python -m camremote`.

**Belongs here:** anything the whole client needs. **Does not belong here:** anything specific to one
verb.

## `camremote.transport` — 3 files, 143 lines

**Role:** carrying bytes to the agent and back, behind an interface narrow enough to replace.

`Transport` is an ABC with one property and one method. Everything above it is written against those
two members, so a second transport is a class and nothing else — and every test above this layer
runs against a fake with no network involved.

**Belongs here:** how to send a request. **Does not belong here:** what to send, or what a reply
means.

## `camremote.commands` — 5 files, 597 lines

**Role:** one module per CLI verb, plus the registry that lists them.

**Belongs here:** argument declarations, the call to the agent, and how to print the result. A verb
is the only place that knows what an operator sees.

**Does not belong here:** protocol details. A verb calls `context.agent.invoke(...)` and formats
what comes back; it never builds an envelope or parses a response itself.

`base.py` holds the two types that shape the rest: `Context` (everything a verb may touch) and
`CliCommand` (one subcommand). `__init__.py` is the registry — **adding a verb is a module and one
line in `COMMANDS`.**

---

# Where a change belongs

| The change | Android | Python |
|---|---|---|
| A new capability | `core/command/impl/` + a line in `di/` | `commands/` + a line in `commands/__init__.py` |
| A new rule or constraint | `core/logic/` | inside the verb, or `client.py` if protocol-wide |
| A new platform mechanism | `app/adapter/` | — |
| A change to the wire format | `core/protocol/` **and** `models.py` | `models.py` **and** `core/protocol/` |
| A new way commands arrive | `app/transport/` | `transport/` |
| Anything about when the agent runs | `app/service/` | — |
| What the operator sees | — | `commands/` |

The rule underneath the table: **a decision goes as far from the platform as it can, and a mechanism
goes as close to it as it can.** Almost every boundary in this project is that sentence applied
once.
