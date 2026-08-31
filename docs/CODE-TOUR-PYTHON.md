# Code tour — the Python control application

A reading order for `python/`, for someone opening it for the first time. About 1,140 lines of
source and 940 of tests, standard library only — no virtualenv, no `pip install`, nothing to set up
before reading or running it.

The Android tour is [CODE-TOUR-ANDROID.md](CODE-TOUR-ANDROID.md). Reading that one first is not
required, but the two halves are shaped alike on purpose and the symmetry is most of the point.

## Before you open anything

**It mirrors the agent's layering deliberately.** Both sides have a transport, a protocol, and a
registry of commands where adding one is a file plus a line. Read the two next to each other and the
correspondence should be obvious — that is the intended reviewing experience.

**Everything is typed in.** There is no discovery and nothing saved between invocations: `--host` is
required on every command, and the agent shows its own address in its notification. There was an
mDNS browser and a `pair` verb that remembered the result; both were removed after measuring how
badly discovery behaved on real handsets ([DEVICES.md](DEVICES.md) has the numbers).

```
python/camremote/
├── cli.py           parser, address handling, exit codes      202
├── client.py        envelopes, error mapping, downloads        93
├── transport/       the seam, and the urllib implementation   143
├── commands/        one module per verb, plus the registry    597
├── models.py        wire-type mirrors                          59
└── errors.py        three typed failures                       30
```

Read it bottom-up: the types, then the transport, then the client, then the verbs, then the CLI that
assembles them.

---

## 1. `errors.py` (30 lines) and `models.py` (59 lines)

Start here; both are short and everything else refers to them.

Three exceptions, and the distinction between them is the whole error model:

| Exception | Means | Exit code |
|---|---|---|
| `TransportError` | the agent could not be reached | 3 |
| `CommandFailed` | the agent was reached and said no | 1 |
| `CamRemoteError` | base class, and client-side problems | 1 |

A script needs to tell "the phone said no" from "the phone was not there", and those are genuinely
different situations — one is a bug or a missing permission, the other is a wrong address or a
sleeping handset.

`models.py` mirrors the agent's wire types: `CommandResponse` and `CommandError`, both frozen
dataclasses. Compare them against `core/protocol/Envelope.kt` — they are the same shapes, and
keeping them so is why neither side has to guess.

## 2. `transport/base.py` (55 lines)

The seam. `Transport` is an ABC with exactly one property (`base_url`) and one method (`request`),
plus a `Response` dataclass that knows how to parse itself as JSON and how to read a filename out of
a `Content-Disposition` header.

It is this small on purpose. Everything above it — the client, the verbs, the CLI — is written
against these two members, so a second transport is a class and nothing else. That claim is not
theoretical: every test in `test_cli.py` runs against a fake client, and `test_client.py` against a
fake transport, with no network anywhere.

## 3. `transport/http.py` (77 lines)

The only implementation. `urllib.request` with a timeout, turning every network failure into a
`TransportError` carrying the address it tried — which is what makes the CLI's unreachable message
useful rather than a stack trace.

## 4. `client.py` (93 lines)

`RemoteClient` is the conversation with one agent, and has two public methods.

`invoke(command, params)` builds the envelope — a fresh UUID as correlation id, the command name,
the parameters — posts it to `/v1/command`, and turns the reply into a `CommandResponse`, raising
`CommandFailed` if the agent reported an error. That is the whole protocol.

`download(path, destination)` fetches a photograph from `/v1/media/{id}` and writes it locally. It
exists because a location on the handset is of no use to the person at the control machine; without
it, "save the image to a specified location" would leave you holding a string rather than a
photograph.

## 5. `commands/base.py` (63 lines)

Two small types that shape everything in the next directory.

`Context` is everything a verb is allowed to touch: parsed `args`, the connected `agent`, the two
output streams, and the `--json` flag. Its `emit()` prints *either* the raw payload or the
human-readable lines, never both — `--json` exists so the CLI can be driven from a script, and
mixing the two would defeat that.

`CliCommand` is one subcommand: a name, help text, a `run` function, optional argument declarations,
and the agent command it invokes. That last field is what lets `camremote commands` print the verb
you can type beside the name the agent uses — declared per verb, so adding one registers its own
mapping rather than requiring an edit to a table somewhere else.

Compare `CliCommand` against `core/command/Command.kt`. Same idea on both sides: one small unit,
one line in a registry.

## 6. `commands/` — the verbs, ~600 lines

`__init__.py` (22) is the registry: import each verb, list it in `COMMANDS`, done. The CLI builds
its parser from that tuple, so adding a verb is genuinely one file and one line.

Read them in this order:

**`getprop.py`** (44) — the smallest, and therefore the best illustration of the pattern. An
argument declaration, a `run` function, a `CliCommand`. Everything else is a variation on it.

**`camera.py`** (154) — three verbs: `open-camera`, `take-picture`, `camera-apps`. `take-picture` is
the one to study: it sends the capture, then downloads the JPEG by default, because the point is
that the operator ends up holding the photograph. Note that `--quality` becomes `jpegQuality` on the
wire — the CLI's flags and the agent's parameter names are related but not identical.

**`system.py`** (314) — the biggest file, because `status` absorbed two verbs that used to stand
alone. It now runs four requests (readiness, camera apps, fourteen build properties, the catalog)
and prints them as one survey, with `--out` to write the JSON a device matrix wants.

The function to read here is `_collect`. It turns a failed section into *part of the answer* rather
than the end of it — a survey that dies on a broken device is no survey, and a broken device is
exactly when you run it.

`_commands` is the other one worth reading: it groups the catalog by the category the agent reports,
falling back to diagnostics for anything uncategorised, so an older agent still lists correctly
rather than losing commands to a client expecting a field it never sends.

## 7. `cli.py` (202 lines) — read last

Now the assembly makes sense. `build_parser()` builds the parser from the `COMMANDS` registry rather
than writing subcommands out by hand — the same shape the agent uses for its own commands, which is
the symmetry this project keeps returning to.

`main()` is the whole flow in about forty lines: parse, split the address, connect, run the verb,
and map every exception to an exit code. The `connect` parameter is a seam, which is why the entire
CLI is testable without a device or a network.

`split_address()` accepts `10.0.0.4` or `10.0.0.4:8099` — the agent's notification reads
`Accepting commands on 10.0.0.4:8099`, so the whole string works without the operator taking it
apart.

Exit codes are at the top of the file and are part of the contract: `0` success, `1` the agent
reported a failure, `2` usage, `3` unreachable.

---

## What to read to understand a change

| To understand… | Read |
|---|---|
| What verbs exist | `commands/__init__.py` |
| What one verb does | `commands/<module>.py` |
| What goes on the wire | `client.py` |
| Why a command exited non-zero | `cli.py`, `main()` |
| How output is printed | `commands/base.py`, `Context.emit` |

## Where the tests are

56 tests, standard library `unittest`, no network and no device:

- **`test_cli.py`** (633) — the bulk. Every verb driven end to end against a `FakeClient`, plus
  address handling, exit codes, and output formatting.
- **`test_client.py`** (183) — envelope construction and error mapping against a fake transport.
- **`test_transport.py`** (128) — the real `HttpTransport` against a `http.server` fixture, which is
  the one place actual sockets are involved.

The layering is what makes this cheap: because `Transport` is one property and one method, faking it
is three lines, and because `connect` is a parameter of `main()`, the CLI is exercised without
either.

## Adding a verb

Write the module, add one line to `COMMANDS`. The parser picks it up automatically. If it drives a
single agent command, set `agent_command=` so `camremote commands` can name it.
[EXTENDING.md](EXTENDING.md) has a worked example.
