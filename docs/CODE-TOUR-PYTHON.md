# Code tour — the Python control application

A reading order for `python/`, for someone opening it for the first time. About 1,140 lines of
source and 940 of tests, standard library only — no virtualenv, no `pip install`, nothing to set up
before reading or running it.

The Android tour is [CODE-TOUR-ANDROID.md](CODE-TOUR-ANDROID.md). Reading that one first is not
required, but the two halves are shaped alike on purpose and the symmetry is most of the point.
[PACKAGES.md](PACKAGES.md) says what each package is responsible for once you need to put something
somewhere.

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
assembles them. Section 8 then follows a single invocation all the way down and back, which is the
quickest way to see how the pieces meet — jump to
[The life of a command](#8-the-life-of-a-command--from-the-shell-to-the-phone-and-back) if you would
rather watch it run than be introduced to it.

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

## 8. The life of a command — from the shell to the phone and back

The sections above introduce the files. This follows one invocation all the way through them, which
is the fastest way to see how they fit. The command is:

```bash
./scripts/camremote --host 10.0.0.4 take-picture --out ./shots
```

`take-picture` is the useful example because it goes one step further than the others: it sends a
command and then follows the reply with a *second* request of its own, to fetch the file that
command produced. Every other verb is this same flow with step 10 left out.

### 1. `scripts/camremote` → `python -m camremote`

The script is eight lines of bash and does one thing: put `python/` on `PYTHONPATH` and exec
`python3 -m camremote`. There is no install step and no virtualenv, because there is nothing to
install. `__main__.py` calls `cli.main()` and exits with whatever it returns.

### 2. `build_parser()` — the parser is generated, not written

```python
for command in COMMANDS:
    subparser = subparsers.add_parser(command.name, help=command.help, ...)
    command.add_arguments(subparser)          # the verb declares its own flags
    subparser.set_defaults(_command=command)  # and rides along in the namespace
```

Four global flags are declared here — `--host` (required), `--port`, `--timeout`, `--json` — and
everything else comes from the registry. `set_defaults(_command=...)` is the small trick that makes
the rest of `main()` verb-agnostic: the parsed namespace carries the `CliCommand` object itself, so
nothing downstream has to map a name back to a function.

`_Parser` overrides `error()` to raise instead of calling `sys.exit`, which is what lets a usage
error become exit code `2` through the same path as everything else — and lets the parser be tested.

### 3. `split_address("10.0.0.4", None)` → `("10.0.0.4", 8099)`

The agent's notification reads `Accepting commands on 10.0.0.4:8099`, so the whole string is
accepted as typed rather than making the operator take it apart. An explicit `--port` is consulted
only when the address does not already carry one.

### 4. `connect(host, port)` — the seam

```python
agent = (connect or _http_client(args.timeout))(host, port)
```

`connect` is a *parameter* of `main()`. In production it is `_http_client`, which builds
`RemoteClient(HttpTransport(...))`. In tests it is a small fake, which is why every test in
`test_cli.py` — all 633 lines of it — runs with no device and no socket. This one parameter is the
whole reason the CLI is testable end to end.

### 5. `Context(...)` → `command.run(context)`

The context is everything a verb may touch: the parsed `args`, the connected `agent`, the two output
streams, and the `--json` flag. Nothing else is reachable from a verb — no `sys.stdout`, no global
config, no transport.

### 6. `_capture()` builds the parameters

```python
params = {}
if context.args.path:      params["path"] = context.args.path
if context.args.filename:  params["filename"] = context.args.filename
if context.args.quality is not None: params["jpegQuality"] = context.args.quality
response = context.agent.invoke("camera.capture", params or None)
```

Two things are deliberate. **A flag the operator did not pass becomes an absent key, not a null** —
the agent's `Params` treats an explicit `null` as absent anyway, but sending nothing at all keeps
the wire honest and lets the agent's own defaults apply. And `--quality` becomes `jpegQuality`: the
CLI's vocabulary and the agent's parameter names are related but not identical, and the verb is the
one place that translation lives.

Note what the verb does *not* do: it never builds an envelope, never names a URL, never touches a
status code. It names a command and formats what comes back.

### 7. `RemoteClient.invoke()` — the envelope

```python
envelope = {"id": str(uuid.uuid4()), "command": command}
if params:
    envelope["params"] = dict(params)
response = self.transport.request("POST", "/v1/command", body=json.dumps(envelope).encode())
```

That is the entire protocol on this side, and it is worth comparing against
`core/protocol/Envelope.kt` — the same three fields, in the same order, mirrored by hand rather than
generated. The `id` is a fresh UUID per request and is echoed back in the reply; it is also what
appears in the agent's logcat line, so a request can be matched to what the phone did with it.

### 8. `HttpTransport.request()` — the wire

`urllib.request` with a `Content-Type: application/json` header and the timeout from `--timeout`
(60 seconds by default — a capture legitimately takes seconds, and a stingy default would turn a
working device into a mysterious failure).

The exception handling is where the interesting decision is:

| What happened | What the transport does |
|---|---|
| a 2xx reply | returns a `Response` |
| `HTTPError` — any non-2xx status | **also** returns a `Response`, with an explicit `with error:` close, since `HTTPError` holds an open socket |
| `URLError`, timeout, `OSError` | raises `TransportError`, naming the address it tried |

A non-2xx status is *not* an error here. The agent answers failures in the same envelope whatever
the status, and that body is usually the most useful thing the operator could be shown — so it is
carried up rather than thrown away. Only being unable to reach the phone at all raises, and the
message it raises with names the address, which is what makes the CLI's unreachable output
actionable instead of a stack trace.

### 9. Back up through `invoke()` — two ways to fail

`_raise_for_transport_status` runs first: a status ≥ 400 has its `error` object read out of the
envelope and re-raised as `CommandFailed` carrying the agent's own `code`, `message` and
`remediation`.

Then `CommandResponse.from_json(...)` parses the body. It is deliberately tolerant — every field
falls back to a default — so a client stays usable against an agent older or newer than itself.
`parsed.ok` is `status == "OK"`; anything else raises `CommandFailed` with the agent's own words,
not the client's guess at them.

So by the time a verb sees a `CommandResponse`, it is a *successful* one. Failure never returns; it
raises, and `main()` catches it in one place.

### 10. The second round trip — fetching the photograph

The payload carries `downloadPath: "/v1/media/kZ8…"`, and `_capture` follows it by default:

```python
saved = context.agent.download(data["downloadPath"], context.args.out)
```

`download()` issues a plain `GET`, then decides where to write: a directory (or a path with no
suffix) is filled in with the filename the agent suggested in `Content-Disposition`, which keeps the
timestamped name the capture was given rather than inventing a new one on this side.

This step is the reason the assignment's "save it to a specified location" is actually satisfied.
Without it the operator would be holding a string naming a folder on a phone they are not near.
`--no-download` turns it off for the case where the photo is meant to stay on the device.

### 11. `context.emit(data, *lines)`

Either the raw JSON or the human-readable lines, **never both** — `--json` exists so the CLI can be
driven from a script, and interleaving prose would defeat that. Diagnostics go to stderr through
`context.warn`, so stdout stays clean enough to pipe into `jq`.

```
Captured 4080x3060, 3.83 MB in 2533 ms
On the device: Documents/cam-remote/camremote-20260830-184015-123.jpg
Saved to: shots/camremote-20260830-184015-123.jpg
```

### 12. `main()` maps the exception to an exit code

Every failure in every layer arrives at one small block of `except` clauses at the bottom of
`main()`, and each maps to an exit code that is part of the contract:

| Raised by | Becomes | Printed |
|---|---|---|
| `CommandFailed` — the agent said no | `1` | `error [CODE]: message`, plus `try: remediation` |
| `TransportError` — the agent was not there | `3` | the address, plus a pointer back to the phone's notification |
| `CamRemoteError` — a client-side problem | `1` | the message |
| `KeyboardInterrupt` | `1` | `interrupted` |
| `_UsageError` — a bad command line | `2` | the complaint and the usage text |

`_UsageError` is caught a few lines earlier, around `parse_args`, because a command line that will
not parse never gets as far as having an agent to talk to.

The `1` / `3` split is the one that matters in a script. "The phone said no" and "the phone was not
there" are genuinely different situations — a missing permission versus a wrong address or a
sleeping handset — and a caller should not have to grep prose to tell them apart.

### The shape of it

```
cli.main          parse, resolve the address, pick the verb, map exceptions to exit codes
  └ commands/     what to send and how to print it
      └ client    the envelope, and what a reply means
          └ transport  bytes there and back
```

Each layer knows only the one below it, and the two seams — `connect` in `main()`, and `Transport`
itself — are why the whole stack can be exercised without a phone.

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

59 tests, standard library `unittest`, no network and no device:

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
