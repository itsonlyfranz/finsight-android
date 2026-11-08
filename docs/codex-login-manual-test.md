# Codex (ChatGPT account) sign-in: manual test

For the captain, to run by hand with a real ChatGPT / Codex account. Everything here was checked against the open-source Codex CLI source (`openai/codex`, `codex-rs/login`, `codex-rs/codex-api`, `codex-rs/models-manager`) and unit-tested against a local fake server. **No end-to-end run with a real account has been done**, so treat the first run as the real verification.

## How it works (what you are testing)
FinSight uses the same *device-code* login the Codex CLI offers for headless machines (`codex login --device-auth`):

1. App `POST https://auth.openai.com/api/accounts/deviceauth/usercode` with the Codex CLI's public client id `app_EMoamEEZ73f0CkXaXp7hrann`. Reply: a `user_code` (e.g. `ABCD-1234`), `device_auth_id`, poll `interval`, `expires_at` (15 min).
   - Probed live on 2026-11-08 from a non-CLI client: HTTP 200 with exactly that shape (`device_auth_id`, `user_code`, `interval: "5"`, `expires_at`). No login was completed during the probe.
2. You open **https://auth.openai.com/codex/device** in any browser (phone or laptop), sign in to ChatGPT, enter the code.
3. App polls `/api/accounts/deviceauth/token` (403/404 = not yet), gets an authorization code + PKCE verifier, exchanges it at `/oauth/token` for `id_token` / `access_token` / `refresh_token`.
4. Tokens are stored encrypted (Android Keystore AES-256-GCM key; ciphertext in a private SharedPreferences file `finsight_secrets`). Access token is refreshed automatically ~2 minutes before it expires, and once after a 401. Refresh tokens rotate, so the new one is saved before the new access token is used.
5. AI calls go to `POST https://chatgpt.com/backend-api/codex/responses` (SSE) with `Authorization: Bearer ...`, `chatgpt-account-id`, `originator: codex_cli_rs`.

## Steps
1. Install/launch the debug build on a device with internet.
2. Bottom bar -> **AI Setup** tab -> **Codex** section. State should read *Signed out*.
3. Tap **Sign in with ChatGPT**. Within a second or two the screen shows a code like `ABCD-1234`, **Open verification page**, and **Copy code**.
4. Tap **Open verification page** (or visit `auth.openai.com/codex/device`), log in to ChatGPT, enter the code, approve. Return to the app.
5. Within ~5-10 s (poll interval) the section flips to **Signed in** showing your email and plan (e.g. `plus`).
6. In AI Setup choose **Codex** as the AI backend.
7. Open **AI Insights** (and **Simulator** / goal cards). Cards should be marked as written by Codex and the text should differ from the deterministic wording. First answer takes a few seconds.
8. Kill the app from recents and reopen: still **Signed in** (tokens restored), Codex answers still work.
9. **Sign out**: state returns to *Signed out*; AI Insights fall back to the built-in (deterministic) text. Reopen the app: still signed out.

## Expected results / failure modes
| What you see | Meaning | What to do |
|---|---|---|
| "Network problem: could not reach OpenAI." | No connectivity or OpenAI unreachable | Check network, tap sign-in again |
| "Device-code sign-in is not available for this account or server." (HTTP 404 on usercode) | OpenAI disabled device-code login for this client/account (workspace admins can) | Try a personal ChatGPT account; capture logs |
| "The sign-in code expired. Start again." | 15 minutes passed without approval | Tap sign-in again |
| "Sign-in was denied." | Approval refused/cancelled in the browser | Retry |
| "Could not complete sign-in (HTTP nnn)." | Token exchange rejected | Capture logs; may mean OpenAI changed the flow or blocked this client id |
| "Your Codex session expired. Please sign in again." | Refresh token expired, was reused or revoked (e.g. you logged out elsewhere) | Sign in again; tokens were already wiped |
| "Codex rejected the saved sign-in. Sign in again..." | 401 even after a refresh | Sign out, sign in |
| "Codex access was refused (HTTP 403). Your ChatGPT plan or region may not include Codex." | Plan/region/workspace policy, or a Cloudflare/originator block | Check the account has Codex access (try `codex` CLI with the same account) |
| "Codex usage limit reached." | HTTP 429, plan limits | Wait |
| "Codex does not offer this model for your account: ..." | Both candidate models (`gpt-5.5`, `gpt-6.1-terra`) rejected | Capture logs; model slugs come from the CLI's bundled `models.json` and may need updating (`CodexTextGenerator.DEFAULT_MODELS`) |
| "Codex rejected the request: ... instructions ..." | Backend wanted a different system-prompt shape; the app already retries once with the other shape, so seeing this means both failed | Capture logs |
| AI cards stay on built-in wording although Codex is selected | Codex call failed and the app fell back (by design) | Look at the AI Setup status line / logcat |

## What to capture if it fails
```
adb logcat -d -s FinSightCodex finsight-llm AndroidRuntime
```
Logs only contain event names, HTTP status codes and exception class names, never codes, tokens or message bodies. Also note the exact on-screen message and the time. To get a clean log: `adb logcat -c` before tapping **Sign in**.

## Honest caveats
- This uses the **Codex CLI's public OAuth client id** and mimics its `originator` header. OpenAI does not publish this flow as a supported third-party API; they can change, throttle or block it at any time.
- ChatGPT plan limits apply to every AI call (free/Plus/Pro/Business have different Codex allowances); there is no API-key billing in this path.
- The inference request body follows the current CLI source (`input` items of type `message` / `input_text`, system prompt as a `developer` message, `store:false`, `stream:true`, `reasoning.effort:"low"`, no tools). It was **not** verifiable without an account; the code falls back to the older top-level `instructions` field automatically if the backend objects.
- Model slugs are best guesses taken from the CLI's bundled catalogue; the one that works for your account is remembered for the session.
- Sign-out is local only (tokens deleted from the device). Revoke the session in ChatGPT settings if you also want it invalidated server-side.
- Tokens cannot be recovered if the device Keystore key is lost (e.g. restored backup onto a new phone): you will simply be signed out.
