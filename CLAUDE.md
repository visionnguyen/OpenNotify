# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

OpenNotify: an internal, single-device Android app (Kotlin, no Compose, plain Views + SQLite) that records notifications from user-selected apps and relays matching ones to per-app webhooks, HMAC-signed. `backend/` is a sample Node.js receiver. The repo directory is named `openPay` but the product/package is `OpenNotify` (`dev.ghien.opennotify`). README.md, code comments, UI strings and log messages are in Vietnamese — match that when editing them.

## Commands

**Android app** (`app/`, minSdk 26, compile/target 34, JDK 17, AGP + Kotlin, only deps: core-ktx, appcompat, recyclerview, work-runtime-ktx):
- There is **no Gradle wrapper**; CI uses system Gradle pinned to 8.7. Build with `gradle assembleDebug` (needs a `local.properties` with `sdk.dir` locally). Output: `app/build/outputs/apk/debug/app-debug.apk`.
- Normal build path is GitHub Actions (`.github/workflows/build-apk.yml`, triggers on pushes to `main` touching `app/**`, root gradle files, or the workflow; also `workflow_dispatch`).
- No unit tests, no lint config.

**Backend** (`backend/`, Node >= 18, ESM, express + dotenv):
- `npm install`, `cp .env.example .env` (set `WEBHOOK_SECRET`; server exits if unset), `npm start` (or `npm run dev` for `--watch`).
- `npm run test:send` — signs a fake event with `WEBHOOK_SECRET` and posts it to the running server (`TEST_URL` overrides `localhost:3000`). This is the only "test"; it needs the server running.

## Architecture

Data flow, spanning several files:

1. `NotifyListenerService.onNotificationPosted` — the entry point (system-bound, works with screen off). Drops the app's own notifications and anything whose package isn't in `tracked_apps`. Extracts raw title/text/bigText/subText/lines (no parsing on device — parsing amounts/order IDs is deliberately left to the backend so regex changes don't need an APK rebuild).
2. `Db.insertNotification` dedupes by `hash` (SHA-1 of package|key|postTime|text|bigText); returns false for already-seen notifications, which stops processing.
3. Each **enabled webhook of that package** is evaluated with `PatternMatcher` against the concatenated text: empty pattern list = match all; `MatchMode.OR` = any regex; `AND` = all regexes. A notification is always stored (browsable in-app) even if no webhook matches.
4. For each matching webhook a `deliveries(hash, webhook_id, sent=0)` row is written **before** sending, then `relay()` POSTs on a single-thread executor under a partial wake lock (4 attempts, linear backoff). `WebhookClient` posts `Payload` JSON with header `X-Signature = hex(HMAC_SHA256(secret, raw_body))` (`Signer`).
5. `OutboxWorker` (WorkManager, ~15 min, scheduled from `onListenerConnected`) resends `sent=0` deliveries and prunes notifications older than 30 days (`olderThanMs`). This is the safety net if the process died mid-send.

Uptime measurement (`Liveness.kt`): the listener posts a 1-minute heartbeat (`probeConnected()` calls `getActiveNotifications` with an empty key list) and marks itself alive on every posted notification. `onListenerConnected` / `onListenerDisconnected` / `onDestroy` open and close rows in `listener_sessions`; gaps between sessions count as downtime (process killed by the OS, reboot), which feeds the uptime bar chart (`UptimeBarView`) in the notification list. If the heartbeat probe fails, the service calls `requestRebind`. `SoundPlayer` plays a sound for each newly stored notification, gated by `Prefs` (SharedPreferences, edited in `SettingsActivity`).

Other pieces: `KeepAliveService` is a near-empty foreground service that only keeps the process from being frozen on aggressive OEM ROMs; `BootReceiver` restarts it after reboot. UI is `MainActivity` (tracked apps + unread counts) → `AppPickerActivity` (add apps) / `NotificationListActivity` (per-app log with uptime chart, marks read, swipe-to-delete) / `AppConfigActivity` (webhook list) → `WebhookEditActivity` (URL, secret, patterns, AND/OR, send-test-event); `SettingsActivity` is reachable from the home screen.

Webhooks follow an **open standard defined by this repo**, `docs/webhook-standard.md` (OpenNotify Webhook v1). Receivers (the sample `backend/`, the mapchat counter PC, anyone else) conform to it; there are no receiver-specific code paths. Manual entry and QR scanning must stay interchangeable: both produce a `WebhookConfig` and go through the same `WebhookConfig.validate()`, and `WebhookQr.parse()` only maps QR keys onto those fields (mapchat's `{v,name,url,id,k,pattern}` QR is just one valid instance). Security is per webhook (`Security.HMAC`: plain payload + `X-Signature`; `Security.AES_GCM`: `{id, iv, ct}` via `AesGcmEnvelope`). The payload (`Payload.kt`) is identical in both modes; its `text` field is also the string patterns match against (`PatternMatcher.inputFor`, optionally normalized). All sending goes through `Sender.post`/`Sender.send`/`Sender.record`, shared by the listener, `OutboxWorker` and the test button; response handling is mode-independent (404/410 → webhook `stopped`, 408/429/5xx/network → retry up to 6 h, other 4xx → drop). Never display or log `Webhook.secret`.

CI signs debug APKs with a fixed PKCS12 keystore from repo secrets `SIGNING_KEYSTORE_B64`/`SIGNING_PASSWORD` (see `app/build.gradle.kts`, `build-apk.yml`) so builds install over each other; local builds use the default debug key.

Schema (`Db.kt`, `SQLiteOpenHelper` version 6; `webhooks` keeps legacy column names: `kind` = security, `pairing_id` = endpoint id, `unpaired` = stopped, because minSdk 26 has no RENAME COLUMN): `tracked_apps`, `notifications` (`is_read` drives unread badge; `is_deleted` is a soft-delete flag), `webhooks`, `webhook_patterns`, `deliveries`, `listener_sessions`. Notifications are stored **unencrypted**; only webhook-matched content leaves the device. `deliveries.sent` is a state (`DeliveryState`: 0 pending, 1 sent, 2 dropped). `onUpgrade` migrates 3→4→5→6 in place but drops and recreates everything when upgrading from below version 3 — add a real migration step for any new schema change rather than bumping the version blindly, or users lose tracked apps and webhooks.

Backend (`backend/src/server.js`): `express.raw` on the webhook route so HMAC is computed over the exact received bytes (don't add a JSON body parser before it); constant-time signature compare; in-memory dedupe on `package|key|post_time` (10 min TTL, lost on restart); appends each accepted event, plus `parseTransaction` output from `src/parse.js`, to `data/events.jsonl` (gitignored). `GET /events` is unauthenticated debug-only. The regexes in `parse.js` are unverified guesses and must be adjusted to real notification samples collected via the app's per-app notification view.

The backend uses a single global `WEBHOOK_SECRET`, while the app stores a secret per webhook — they must be set to match when testing end to end.
