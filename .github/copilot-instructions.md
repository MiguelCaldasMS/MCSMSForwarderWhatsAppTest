# Copilot Instructions — MC SMS Forwarder (Multi-Channel)

## Build

```powershell
.\gradlew.bat :app:assembleDebug          # build debug APK
.\gradlew.bat :app:installDebug           # build + install on connected device/emulator
```

No test suite or linter is configured.

## Architecture

Single-module Android app (`:app`), Kotlin, no Compose — XML layouts with Material 3.

**Pipeline** (`SmsReceiver`): incoming SMS → master kill-switch (`prefs.getBoolean("master_enabled", true)`,
default ON) → bail if **no channel is operational** (each channel: enabled toggle on AND credentials
present) or senders / regexes are empty → reassemble multipart → **SMS loop guard** (drop messages
from the SMS forward destination via `PhoneNumberUtils.areSamePhoneNumber`; SMS channel only) →
match sender against allowed list via `SenderMatcher` (`PhoneNumberUtils.areSamePhoneNumber` +
case-insensitive exact match for alphanumeric IDs) → normalize body with
`TextNormalizer.normalizeForMatching` (NFD + strip combining marks + lowercase) → compile each
regex once and match any (`runCatching` per pattern; invalid patterns silently skip) → apply
optional `ForwardTemplate` (`%s`/`%t`/`%m` tokens) → `goAsync()` keeps the receiver alive →
**fan out the same body to every operational channel** (`WhatsAppCloudChannel`, `TelegramChannel`,
`SmsChannel`). A shared `AtomicInteger remaining` counts pending channel callbacks; each
channel's `onComplete(success)` decrements it, and when it reaches zero the receiver records
**exactly one** forward stat (if any channel succeeded) via `ForwardStatsStore.recordForward` and
calls `pending.finish()`. The original (accented, cased) body is what gets forwarded —
normalization is only for matching.

**Loop guard (SMS only).** A message arriving from the SMS forward destination is suppressed so an
SMS→SMS echo cannot bounce indefinitely. WhatsApp and Telegram run on a different transport and
cannot re-trigger the pipeline, so the guard is scoped to the SMS channel's destination.

**Channel pattern.** Each channel is a `XxxConfig` immutable data class (`enabled` flag + fields +
`hasCredentials`/`isOperational` + `load(prefs)` + `KEY_*` consts) paired with a `XxxChannel`
`object` exposing `send(context, config, body, onComplete: (Boolean) -> Unit = {})`. Channels log
`SEND OK [Channel]` / `SEND FAILED [Channel]` and never log secrets. **Channels never record
stats** — `SmsReceiver` owns the single increment per matched SMS.

**WhatsApp Cloud channel** (`util/WhatsAppCloudChannel.kt`): `object` with a single-thread daemon
`Executor` named `wa-sender`. `send` builds JSON via `buildPayload` (free-form `text` when
`useTemplate=false`, otherwise a template with a single body parameter `{{1}}` bound to `body`),
strips the leading `+` from the recipient, opens `HttpURLConnection` to
`https://graph.facebook.com/v21.0/{phoneNumberId}/messages`, writes the body with
`setFixedLengthStreamingMode`, sets `Authorization: Bearer …`, 10 s connect / 20 s read timeout,
then logs `SEND OK [WhatsApp] → {recipient} (HTTP {code})` or the matching `SEND FAILED` with the
Meta `error.{code,type,message}` summary. The access token never appears in logs.

**Telegram channel** (`util/TelegramChannel.kt`): sibling `object` on a `tg-sender` daemon thread.
POSTs `chat_id`+`text` (web previews disabled) to `https://api.telegram.org/bot{token}/sendMessage`
(token URL-encoded), logs `SEND OK/FAILED [Telegram]` with the Telegram `error_code`+`description`
summary. The bot token never appears in logs.

**SMS channel** (`util/SmsChannel.kt`): re-sends through the device modem via
`SmsManager.sendMultipartTextMessage` (obtained with `getSystemService(SmsManager::class.java)`,
API 31+). `onComplete(true)` fires when the message is successfully *handed to the modem* (no
exception), mirroring how the HTTP channels treat 2xx — neither guarantees delivery. A private,
dynamically-registered `BroadcastReceiver` (action `…SMS_SENT_RESULT`, `RECEIVER_NOT_EXPORTED`)
logs the modem's asynchronous per-segment result (`SEND OK [SMS]`, `no service`, `radio off`, …).
Needs the `SEND_SMS` permission. The only "credential" is the destination number; there is no token.

**Master switch**: `MasterSwitchTileService` (Quick Settings tile) and the main-screen
`MaterialSwitch` both write the same `master_enabled` pref; `MainActivity.onResume` re-syncs
the switch in case the tile flipped it while paused.

**Boot**: `BootReceiver` exists purely to make the framework load the package on
`BOOT_COMPLETED` (no real work; just a log line) so the manifest SMS receiver is warm before the
first message.

**Persistence**: everything is in a single `SharedPreferences` file named `mc_sms_fwd_wa`. No
database. Lists (senders, regexes) are newline-delimited strings. Logs use a
`timestamp\x1Fmessage` format with auto-pruning (35 days / 2000 entries); `LogUtils.addToLog`
collapses CR/LF/`\x1F` runs in the message to a space so multi-line bodies can't corrupt the
line-oriented format. WhatsApp credentials live under keys defined in `WhatsAppConfig`:
`waPhoneNumberId`, `waAccessToken`, `waRecipient`, `waUseTemplate` (default true), `waTemplateName`,
`waTemplateLanguage` (default `en_US`), `waEnabled` (default true). Telegram: `tgEnabled` (default
false), `tgBotToken`, `tgChatId` (`TelegramConfig`). SMS: `smsEnabled` (default false) and
`forwardTo` — the destination key is reused from the legacy single-channel app so a migrated
install keeps its number (`SmsConfig`).

**Activities** are plain `AppCompatActivity` subclasses — no fragments, no ViewModel, no
navigation component. Settings fields are **debounced-saved** (~150 ms after the last keystroke
via `Handler.postDelayed`) and force-flushed in `onPause()` via `flushPendingWrites()`. Dynamic
rows that share an `EditText` id (e.g. `R.id.senderEntry`) set `isSaveEnabled = false` so view-
state restore doesn't copy the last-focused row's text onto every row after recreation.

## Conventions

- **Util objects** in `util/` are Kotlin `object` singletons (not classes). They take
  `SharedPreferences` or `Context` as parameters — no dependency injection. Current set:
  `SenderListStore`, `RegexListStore`, `SenderMatcher`, `TextNormalizer`, `ForwardTemplate`,
  `ForwardStatsStore`, `WhatsAppConfig`, `WhatsAppCloudChannel`, `TelegramConfig`,
  `TelegramChannel`, `SmsConfig`, `SmsChannel`, `LogUtils`.
- **Edge-to-edge** + insets handling is repeated in every activity's `onCreate` using
  `enableEdgeToEdge()` and `ViewCompat.setOnApplyWindowInsetsListener`.
- **Regex matching** uses `TextNormalizer.normalizeForMatching` (NFD + strip combining marks +
  lowercase) so patterns can be written accent-free and case-free. Invalid regexes are
  silently treated as non-matches.
- **Version catalog** (`gradle/libs.versions.toml`) manages all dependency and SDK versions;
  `app/build.gradle.kts` references them via `libs.*`.
- Release signing is opt-in via Gradle properties (`RELEASE_KEYSTORE_PATH`, etc.). No keystore
  or access token is committed to the repository.
- The access token is stored as plain text in `SharedPreferences` for this test variant — never
  write it to logs, never include it in error messages, never paste it into bug reports. The same
  rule applies to the Telegram bot token. The SMS channel has no secret (it uses the device modem).
- **`RegexTesterActivity` is a dry-run mirror of the live pipeline.** It must evaluate the same
  channels `SmsReceiver` does (all three, via each config's `isOperational`); if you add or change
  a channel, update the tester so the two cannot drift.
