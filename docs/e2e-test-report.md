# Full-app end-to-end test pass (v1.1.0)

Instrumented Compose/Espresso coverage for every feature of the app, run on a dedicated emulator
(`finsight_e2e_api36` on `emulator-5592`; the captain's emulator was never touched). The network is
always faked: a local fake Hugging Face / download server (`FakeHubServer`, real HTTP + `Range`), a fake
Codex device-code client, and a fake llama.cpp runtime. Everything else (activity, view models, SQLite,
SharedPreferences, Keystore secrets, real `HttpHfApi` / `HttpModelDownloader`) is real.

## Coverage map: what existed, what was added

| Feature | Before this PR | Added here |
|---|---|---|
| Dashboard (profile, outflow, target, buffer, breakdown, links) | none (unit only for the view model) | `DashboardSimulatorE2ETest` |
| Transactions: add | none | `TransactionsE2ETest` (expense, income, validation, cancel) |
| Transactions: edit | none | Edit dialog added after this pass: `TransactionsE2ETest` (prefill, amount/category/note/date, income vs expense, validation, cancel); repository edit in `SqlitePersistenceE2ETest` |
| Transactions: delete | none | `TransactionsE2ETest` (confirm / cancel, totals update) |
| Transaction filters | none | `TransactionsE2ETest` (every category, All, empty state, survives tab switch) |
| CSV export | `CsvExporterTest` (string only) | `TransactionsE2ETest`: the shared file's contents, numeric amounts, filter, escaping |
| AI insights, rules fallback | `AiFeaturesE2ETest` | `CodexAndBackendsE2ETest`: no model, Codex signed out, Codex failing + recovery |
| What-if simulator | unit only | `DashboardSimulatorE2ETest`: default levers, slider changes, baseline change after adding an expense |
| Savings goals | unit only | Preset goals removed after this pass: `DashboardSimulatorE2ETest` and `EmptyStateE2ETest` check the empty goals state; goal timelines are unit-tested (`ForwardSimulatorEngineTest`) |
| Themes | none | `ThemesPersistenceE2ETest`: every theme, painted background, saved, all tabs |
| Settings (theme, backend, HF token, Codex model) | partly (`AiSetupE2ETest`, in memory) | `SettingsPersistenceE2ETest` + `ProcessRestartE2ETest` on real storage |
| Persistence: activity recreation | none | `ThemesPersistenceE2ETest`, `SettingsPersistenceE2ETest` |
| Persistence: restart | none | in-app restart (new object graph, same disk) in the above; real process death via `scripts/e2e-process-restart.sh` |
| Peso display | unit (`MoneyTest`) | no `$` on any screen (`DashboardSimulatorE2ETest`) |
| On-device model picker / curated list | `AiSetupE2ETest` | unchanged |
| HF search + compatibility filter | `AiSetupE2ETest` (fake `HfApi`) | `OnDeviceModelsE2ETest` over real HTTP: filter, empty, 500/429 recovery, token only sent when saved |
| Model download, cancel, resume, retry | `AiSetupE2ETest` (fake downloader), `RealDownloadE2ETest`, `HttpModelDownloaderOnDeviceTest` | `OnDeviceModelsE2ETest`: tiny GGUF, verified bytes, cancel removes `.part`, `Range` resume, dropped-connection resume, checksum failure, 500 + retry, gated 401 + token |
| Model select / delete (incl. active) | `AiSetupE2ETest` | `OnDeviceModelsE2ETest`: select moves badge, delete active falls back and keeps the other, insights revert, survives restart |
| Codex sign-in / backend switching | `AiSetupE2ETest`, `AiFeaturesE2ETest` | `CodexAndBackendsE2ETest`, `SettingsPersistenceE2ETest`: via the real activity, model switch, sign-out, persisted sign-in |
| R8 release smoke | `ReleaseShrinkSmokeTest` | re-run on the dedicated emulator |

## Results (this branch)

| Check | Result |
|---|---|
| `testDebugUnitTest` | 176 tests, 0 failures |
| `lintDebug` | passes |
| `connectedDebugAndroidTest` (debug, API 36 emulator) | 114 tests, 0 failures, 1 skipped (the on-device model test that needs a downloaded model) |
| `scripts/e2e-process-restart.sh` (write, `am force-stop`, verify in a new process) | pass |
| Shrunk release: launch + `ReleaseShrinkSmokeTest` (`-PciDebugSigning -PciReleaseTests`) | pass, 0 crashes in logcat |

## Findings

Items 1-4 were found in this pass and have since been resolved in a follow-up PR:

1. **No way to edit a transaction.** Resolved: tapping a row opens an edit dialog (amount, category, date,
   note) backed by `FinSightRepository.updateTransaction`.
2. **Deleting every transaction brought the demo data back on the next start.** Resolved by not seeding
   demo data at all: a fresh install starts empty with empty states on every screen, and nothing re-adds
   sample data. Existing installs are untouched. Tests build their own data (`SampleData`).
3. Minor: the "401(k) employer match" insight and the hard-coded "₱35,000+ in 12M" on the dashboard
   simulator card. Resolved: a 13th-month-pay tip, and the card computes the figure from the user's own
   cafe and tech spending (or hides it when there is none).
4. Minor UX: the Add Expense button covered the delete icon of a row at the bottom of the list. Resolved:
   the button sits in a bottom bar, so the list ends above it.

Still open:

1. Infra: CI's "Stop emulator" step runs `adb emu kill` on every emulator, so concurrent runs and
   local emulators die (observed twice while writing this). Out of scope here (separate held task).
   The `main` push run 42557240033 failed in "Smoke-test shrunk release on emulator", consistent with this.

## Running it

```
SERIAL=emulator-5592 ANDROID_SERIAL=emulator-5592 bash gradlew connectedDebugAndroidTest
SERIAL=emulator-5592 bash scripts/e2e-process-restart.sh
```
