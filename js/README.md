# js/ — the engine package

DeepSeek Harness (dsh) **0.2.1-rc.2**, installed unmodified from npm and customized only by a dsh bundle (profile
`android`), three plugins, one vendored third-party LLM adapter and a few patches applied at build time. The app side of the interface is
[`../docs/ENGINE-CONTRACT.md`](../docs/ENGINE-CONTRACT.md).

## Layout

| Path | What |
|---|---|
| `engine/package.json`, `engine/package-lock.json` | npm project whose production install is the engine tree. dsh is pinned exactly; the lockfile pins every transitive package. |
| `packages/bundle-android/` | the dsh bundle behind profile `android` (`cordis.patch.yml`) and the `android-env.cjs` preload |
| `packages/plugin-device/` | capability bridge client (`DSH_BRIDGE_FD`) → dynamic dsh tools |
| `packages/plugin-guard/` | hardline shell floor, approval policy, budgets, phone system prompt |
| `packages/plugin-mode/` | session modes: per-conversation tool restriction, skills switch, mode prompt section (`android/session/mode`, ENGINE-CONTRACT §2.5) |
| `packages/plugin-account/` | DeepSeek account login: loopback OAuth callback server + `account/*` SDK methods |
| `packages/llm-service-lite/` | vendored `@snnh/dsh-llm-service-lite` 0.2.1 (MIT, snnh), with its upstream `lib/index.js` kept verbatim. Android changes are applied by patches 14 (transport diagnostics), 16 (OpenCode session headers) and 17 (nullable stream usage); re-check them on an adapter upgrade. |
| `patches/*.patch` | unified diffs against `node_modules/`, applied in order with `patch -p1` (dry-run first) |
| `scripts/fetch-locked.mjs` | installs lockfile-pinned packages `npm ci` skips on the host (sharp's WebAssembly build), sha512-verified |
| `scripts/unreachable.mjs` | lists packages reachable only through given deps (used to prune pi-ai's provider SDKs) |
| `scripts/make-zip.py` | deterministic zip (sorted, fixed mtimes, rejects symlinks) |
| `scripts/bench-cold-start.mjs` | host cold-start benchmark (spawn → `initialize`) |
| `test/hardline.test.mjs` | hardline unit tests: `node --test js/test/hardline.test.mjs` |
| `test/prompt.test.mjs` | system prompt section tests: `node --test js/test/prompt.test.mjs` |
| `test/mode.test.mjs` | plugin-mode unit tests (categories, payload validation, restriction / guard / record on a fake context): `node --test js/test/mode.test.mjs` |
| `test/provider-network.test.mjs` | patches 16/17 against a built stage tree: `DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/provider-network.test.mjs` |
| `test/opencode-session.test.mjs` | OpenCode header scope, per-conversation identities and explicit-header preservation: `node --test js/test/opencode-session.test.mjs` |
| `test/host-opencode-session.mjs` | real built-adapter request/stream regression, no API key or native bindings: after building, `node --test js/test/host-opencode-session.mjs`; `E2E_ENGINE_DIR` can point at an extracted `engine.zip` |
| `test/host-e2e.mjs` | host end-to-end run of the built `engine.zip` with a fake bridge |
| `test/host-ac8-resume.mjs` | host repro of engine kill + `session/resume` + continuation |

Our packages are plain ESM with no dependencies outside `node:` built-ins; there is no compile step (the vendored adapter is shipped as built upstream).

## Build

```sh
js/build-engine.sh
```

Paths are relative to the repo root; `toolchains/` is gitignored.

| Env | Default | |
|---|---|---|
| `OUT_DIR` | `toolchains/out/engine` | `engine.zip`, `engine.sha256` (`sha256sum` format), `engine-version.txt`, `size-report.txt` |
| `WORK_DIR` | `toolchains/out/engine-build` | staging tree; `host-native/` holds host bindings for tests |
| `TMPDIR_OVERRIDE` | `toolchains/tmp` | exported as `TMPDIR`; `/tmp` is not used |

Gradle packages `engine.zip` and `engine.sha256` from `-Pdsh.engineOut=<dir>` (default `toolchains/out/engine`, the same as `OUT_DIR`).
Requires node/npm, `patch`, `python3` and access to the npm registry.

Steps:

1. `npm ci --omit=dev --ignore-scripts` in a copy of `engine/` (flat hoisted layout). `overrides: {zod: 4.4.3}`: dsh
   pins zod exactly in ~60 packages; without the override npm nests ~50 copies (~250 MiB). Install scripts are not
   needed (node-pty/koffi only pick prebuilds, which are pruned).
2. Copy the four `@dsh-android/*` packages and the vendored `@snnh/dsh-llm-service-lite` into `node_modules/` (copies,
   not links) and add them to the root `package.json`. The adapter's dependencies (`eventsource-parser`, cosmokit,
   schemastery, dsh-util-values) must already be installed from the lockfile at versions satisfying its ranges.
3. Add `@img/sharp-freebsd-wasm32`, `@img/sharp-wasm32`, `@emnapi/runtime`, `tslib` at their lockfile versions (patch 09).
4. Save the host's native bindings to `$WORK_DIR/host-native/libdsh_*.so` (tests only, never zipped).
5. Apply `patches/*.patch`; fail on rejects or fuzz.
6. Prune: packages reachable only through `llm-pi-ai` (openai, anthropic, google, aws SDKs; the row is disabled),
   `libreoffice-kit-wasm`, `sherpa-onnx*`, the web client UI and inspector, native `@img/sharp-*`, other-platform native
   packages, node-pty prebuilds/sources, every `*.node/.dll/.dylib/.exe`, `.bin`, `*.map`, `*.d.ts`, READMEs, and
   package-root `docs|test|tests|examples|benchmark`.
7. Verify: no symlinks, entry point present, patch markers present.
8. `engine-version.txt` = `<dsh version>-a<10 hex of the sha256 of all inputs>`; deterministic zip; sha256; size report.

Zip layout (= `ENGINE_DIR` on the device):

```
package.json  package-lock.json  engine-version.txt
node_modules/@deepseek-ai/dsh/lib/bin.js       ← entry point
node_modules/@dsh-android/{bundle-android,plugin-device,plugin-guard,plugin-mode,plugin-account}/
node_modules/…                                  ← flat npm tree
```

## Profile `android`

Patch 07 adds `android` to dsh's shipped profile templates, so `--profile android` creates
`$DSH_HOME/profiles/android/` on first launch; the app has no init step. The profile stacks three bundles:

1. `@deepseek-ai/dsh-base`: core rows (tools, persistence, approval, DeepSeek web search, account provider).
2. `@deepseek-ai/dsh-sdk-app`: stdio JSON-RPC SDK server.
3. `@dsh-android/bundle-android` ([`cordis.patch.yml`](packages/bundle-android/cordis.patch.yml)):
   - persona "DSH, an AI agent running on the user's own Android phone";
   - `sandbox-policy` → `danger-full-access` with `workspaceRoot = $HOME`, `approval` → `ask`. Android has no
     bwrap/landlock/seatbelt; safety comes from approvals and the hardline floor;
   - disabled rows: `plugin-manager`, `session-telemetry-otel`, `otel`, `llm-pi-ai`, `ptc-runtime`, `workflow-ptc`,
     `tool-workflow`, `skill-office`, `workspace-dependencies`, `tmux-context` (otel + pi-ai halve boot-time modules);
   - inserted rows: `dsh-time-context`, `dsh-tool-ask-user`, `android-device`, `android-guard`, `android-account`.
   - Model default stays dsh-base's `deepseek-official` / `deepseek-flash`.

Only the bundle list is persisted in `DSH_HOME`, so later engine versions can change `bundle-android` freely. User
overrides go in `$DSH_HOME/profiles/android/cordis.patch.yml`. Inspect the composed tree with
`node …/bin.js --profile android --dump-config`.

`android-env.cjs` (loaded with `--require`) restores what `AT_SECURE` makes Node ignore: `os.tmpdir()` from `TMPDIR`,
the compile cache, extra CA certificates, and `TZ` from `DSH_ANDROID_TZ`.

## Plugins

### plugin-device

- Opens `new net.Socket({ fd: DSH_BRIDGE_FD })` (unref'd; SDK stdin owns process lifetime).
- `capability/list` at activation (with retries) and on every `capability/changed`; each `ToolDescriptor` becomes a dsh
  tool with its JSON Schema. Changed or removed tools are unregistered first; names that collide with dsh built-ins are
  skipped. The description gets a footer with group, risk and unlock behaviour.
- Approval: a `tools/pre-execute` listener returns `ask` for `write | sensitive | dangerous`; dsh then sends
  `approval/request` to the app. `read` runs directly.
- Timeouts (contract §3.1): dsh tool timeout = `timeoutMs + 15 s`, bridge request timeout = `timeoutMs + 10 s`;
  `capability/cancel` on timeout or abort.
- `capability/invoke.sessionId` is always the root SDK session, so automation whitelists apply to subagents too.
- `ok:false` → tool error `"[<code>] <message>"`.
- Images go to `ctx.attachments` and are returned as image blocks if the model route accepts images, otherwise as a
  short text note.
- Without `DSH_BRIDGE_FD` it logs once and registers nothing.

### plugin-guard

- **Hardline** ([`hardline.js`](packages/plugin-guard/hardline.js)) for `bash`, `pwsh`, `terminal_*`. Shell-aware parse:
  quotes, `$(…)`, backticks, `sh -c`, `su -c`, `eval`, `xargs`, `env`/`sudo`/`busybox`/`toybox` prefixes,
  `$EXTERNAL_STORAGE`-style variables, globs, `cd` earlier in the command, small scripts run via `sh x.sh`. Blocks
  recursive rm of `/`, `/sdcard`, `/storage…`, `/data…` and their contents; any rm under `/system`, `/vendor`, `/apex`,
  `/proc`, `/dev`, …; `find -delete` there; writes to block devices, `mkfs`, partition tools; reboot/shutdown/power
  intents; fork bombs; decoded payloads or `curl` piped into a shell; uninstalling system packages; `settings delete`;
  wipe and factory reset. Enforced by a prepended `tools/pre-execute` listener plus a `ctx.tools.guard` backstop. The
  model gets `Error: HARDLINE_BLOCKED (rule: …): … Do not retry`. The parser is heuristic: commands assembled at runtime
  are not resolved.
- **Approvals**: `askTools` (`bash`, `write`, `edit`) ask, except read-only shell commands (`ls`, `cat`, `grep`,
  `getprop`, `dumpsys`, `settings get`, `pm list`, … without output redirects) and `write`/`edit` inside
  `HOME`/`TMPDIR` unless the mode is read-only or unknown. A `sensitive` device tool asks once per conversation. The
  conversation's mode arrives via `android/session/permission` (patch 10 listener). Unattended (`auto_…`) sessions get a
  runtime note and automatic "nobody is available" answers to `user-question/ask`.
- **Budgets**: limits from `budget/update`. Usage is metered from `assistant/message` / `assistant/attempt` usage,
  attributed to the root session, stored in `$DSH_HOME/android/budget.json` (local-day rollover). Token limits count
  cache reads at their price ratio (0.1). When a limit is reached the next step is rejected at `agent/pre-step` (turn
  ends `blocked`) and `budget/exceeded` is sent on the bridge. Cost is estimated from CNY per 1M tokens
  `{input: 2, cacheRead: 0.2, output: 3}`, overridable via the row's `prices`.
- **System prompt** section `android:phone-context`: tool priority (system API > intent/deep link > Shizuku > screen
  automation), approvals, destructive-operation scope, `/system/bin/sh` + toybox, reply language from
  `DSH_ANDROID_LOCALE`.
- dsh `ctx.logger` warnings/errors are mirrored to stderr (`engine.log`).

### plugin-account

dsh-base already provides the account provider (`deepseek-account`: OAuth + PKCE S256 against
`https://platform.deepseek.com`, token in `$DSH_HOME`, profile/balance, sign-out) and the `deepseek-account` model
route. The plugin adds:

- a loopback `webServer` on `127.0.0.1:<OS-assigned port>`, opened on the first sign-in, for `/oauth/callback`. Its
  success/failure/cancel answers are replaced by a page that opens
  `intent://account/login?result=ok|failed|cancelled#Intent;scheme=dsh-android;package=$DSH_ANDROID_PACKAGE;end`;
- SDK methods `account/state`, `account/details`, `account/sign-in/start`, `account/sign-in/cancel`,
  `account/sign-out`, `account/tasks/running` and notifications `account.*` (patch 10's `sdkExtensions` service;
  contract §2.4).

The app uses `deepseek-account` only when no API key is set; the engine starts without `DEEPSEEK_API_KEY` so login can run.

## Patches

Targets must be stable file names. `build-engine.sh` refuses a patch whose target is missing and maps a content-hashed
chunk name (`name-XXXXXXXX.js`) to the single current chunk of that name, so a dsh upgrade fails loudly instead of
silently skipping. Platform patches are inert on desktop Linux unless the corresponding env var is set (the host e2e sets
`DSH_NATIVE_DIR` to exercise the Android code paths).

| Patch | Package(s) | What / why |
|---|---|---|
| `01-flock-android` | `node-addon-system` | `flock` threw `ERR_FLOCK_UNSUPPORTED_PLATFORM` on android (the session write lease needs it). Loads `$DSH_NATIVE_DIR/libdsh_flock.so` via `process.dlopen`. |
| `02-require-builtin-expose-internals` | `node-addon-require-builtin` | dsh uses this addon only to reach Node *internal* modules (`internal/modules/esm/loader`, …), which `process.getBuiltinModule` does not serve. The engine runs with `--expose-internals` (argv only; Node refuses it in `NODE_OPTIONS`) and the patched entry uses plain `require()`, so no native binding ships. Without the flag the upstream path runs unchanged (on android it throws a clear error). |
| `04-shell-system-bin-sh` | `dsh-bash-local` | `bash -c` → `/system/bin/sh -c` on android (override: `DSH_BASH_SHELL`). |
| `05-hardlink-copy-fallback` | `dsh-attachment-local`, `dsh-session-persistence-jsonl` | `link(2)` is denied in app storage; fall back to exclusive copy to a sibling temp + rename, keeping `EEXIST` semantics. |
| `06-ripgrep-from-path` | `dsh-tool-fs-search` | `@vscode/ripgrep` has no android build: use `DSH_RG_PATH`, else `rg` from `PATH` (`files/bin/rg → librg.so`). |
| `07-android-profile-template` | `dsh-app-boot` | adds `android` to the shipped profile templates. |
| `08-sdk-approval-user-questions` | `dsh-sdk-jsonrpc-server` | rc.2's SDK server lacks `approval/request` / `user-question/ask`; backport from 0.2.1-rc.3. **Drop when upgrading** (it would double-register). Without it every approval fails closed. |
| `09-sharp-wasm-android` | `sharp` | no bionic libvips: on `android-*` sharp loads its WebAssembly build. Attachments (tool images, prompt images) need it. |
| `10-sdk-extension-methods` | `dsh-sdk-jsonrpc-server` | unknown SDK methods resolve through an optional `sdkExtensions` service or a `sdk-extensions/resolve` listener (works before `initialize`); plugins emit SDK notifications with `ctx.emit('sdk-extensions/notify', method, params)`. Used by plugin-account, plugin-guard and plugin-mode. Re-check on every dsh bump. |
| `11-sdk-prompt-time-zone` | `dsh-sdk-jsonrpc-server` | SDK prompts carried no time zone, so `dsh-time-context` reported "time zone unavailable". Prompts now carry `clientTimeZone` from `session/prompt`, else the process `TZ`. |
| `14-llm-lite-transport-cause` | `@snnh/dsh-llm-service-lite` (vendored) | a fetch failure surfaced as a bare `provider transport failed`: the cause chain does not reach the app. The message now carries the root cause and the target, e.g. `…: ECONNREFUSED 192.168.1.5:8080 (connection refused)` (undici's `cause` / `AggregateError` attempts, TLS codes; URLs reduced to their origin, never the key). Code stays `TRANSPORT` for any fetch `TypeError`; bare-code throws also count `EHOSTUNREACH`/`ENETUNREACH`/`UND_ERR_HEADERS_TIMEOUT`/…, not `ENOTFOUND`. Exports `classifyTransport` / `describeTransportCause` for the unit test. The app maps the code to a hint (`NetDiagnose`). |
| `15-http-proxy-exclusion-globs` | `dsh-http-proxy` | `NO_PROXY` entries with a `*` other than a leading `*.` match as a whole-host glob, so Android exclusion lists (`192.168.*`, `10.*`) bypass the proxy as they do for `HttpURLConnection`. |
| `16-llm-service-lite-opencode-session` | `dsh-llm-service-lite` | OpenCode/Go requires `x-opencode-session`. At the shared request boundary, add the harness conversation id only for `opencode.ai` and its subdomains; preserve an explicitly configured header. Sessionless calls get an isolated UUID. Applies on the host too, so the packaged adapter can be tested without Android. |
| `17-llm-service-lite-null-usage` | `dsh-llm-service-lite` | Chat Completions streams may carry `usage: null` before the final accounting frame. Ignore those placeholders instead of reading `prompt_tokens` from null and misreporting a successful completion as a transport failure. Applies on the host too. |

Not patched: `terminal-bash` (`/bin/bash` default) and `dsh-api-workspace-controller` are not mounted by
dsh-base/sdk-app; `subprocess-local`'s `/bin/sh` fallback is Linux-only (Android uses plain `child_process.spawn`; its
one-time warning about process-tree containment is expected); `sandbox-local` is handled by profile config. node-pty is
not patched; `libdsh_pty.so` ships but is unused while no terminal row is mounted.

## Host tests

```sh
node js/test/host-e2e.mjs [engine.zip] [host-native-dir]
E2E_PHASES=C,D node js/test/host-e2e.mjs          # account phases only, no API key
```

Defaults: `toolchains/out/engine/engine.zip` and `toolchains/out/engine-build/host-native`; scratch under
`toolchains/tmp/`. The API key is read from the file named by `DSH_E2E_KEY_FILE` (default
`~/.config/dsh/deepseek_api_key`) and never printed. Other knobs: `E2E_ENGINE_DIR` (reuse an extracted tree),
`E2E_MODEL`, `E2E_KEEP=1` (keep scratch).

Host bindings are installed under their Android file names with `DSH_NATIVE_DIR` set, so the patched loaders run.

- **A**: boot without a bridge; profile self-initializes; cwd is `HOME`; the missing bridge is logged once.
- **B**: a fake bridge on fd 3 with `file_list` (read), `file_write` (write) and `screen_capture` (read, needsUnlock);
  real `deepseek-flash` prompts check read-without-approval, `approval/request` for writes, results flowing back, a
  hardline block of `rm -rf /sdcard` (via a test-only `--patch` persona overlay, since the shipped prompt makes the model
  refuse to try), and a budget-refused turn. Also checks that sharp loads its WebAssembly build as `android-arm64`.
- **C**: account login against a local fake Platform (PKCE, bad `state`, failed exchange, success page, details,
  re-`initialize` onto `deepseek-account`, 401 → session expired, sign-out, cancel).
- **D**: real `https://platform.deepseek.com` up to the authorize URL; forged callbacks rejected; cancel. Completing a
  real login needs a person.
- **E**: B with `process.platform` faked to `android` (approval policy, time zone, unattended runs).

Not covered on the host: the bionic addons, `/system/bin/sh`, the `link(2)` fallback, image attachments reaching an
image-capable model, a real login through the in-app WebView.

## Size

`size-report.txt` (next to the zip) records `node_modules` size before and after pruning, the zip size and the largest
packages.
