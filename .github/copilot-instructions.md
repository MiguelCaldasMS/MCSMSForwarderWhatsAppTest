# Copilot Instructions — MC SMS Forwarder (Multi-Channel)

## Build

```powershell
.\gradlew.bat :app:assembleDebug          # build debug APK
.\gradlew.bat :app:installDebug           # build + install on connected device/emulator
```

No dedicated test suite is configured. Use Gradle lint for static checks.

## Architecture

Single-module Android app (`:app`), Kotlin. The UI is **Jetpack Compose** (Material 3): a single
`MainActivity : ComponentActivity` calls `setContent { MCSmsForwarderTheme { AppRoot() } }`, and
`AppRoot` hosts a `NavController` that routes between screens (status, channels, filters, log).
Each screen has an `AndroidViewModel` exposing `StateFlow` draft state.

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
`Executor` named `wa-sender`. The message template is **fixed in code** (constants `TEMPLATE_NAME`,
`TEMPLATE_LANGUAGE`, `TEMPLATE_TITLE`) — it is intentionally not selectable in the config
or the UI. It points at the approved `titled_forwarded_sms` template, whose body has two `{{n}}`
parameters: `{{1}}` is bound to the fixed user `TEMPLATE_USER` (`"Miguel"`) and `{{2}}` to the
forwarded SMS body. `send`
builds the template JSON via `buildPayload`, strips the leading `+` from the recipient, opens
`HttpURLConnection` to
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

**Master switch**: `MasterSwitchTileService` (Quick Settings tile) and the Status screen's Compose
`Switch` both write the same `master_enabled` pref. `StatusViewModel` registers an
`OnSharedPreferenceChangeListener`, so flipping the tile reactively updates the on-screen switch
(no `onResume` re-sync needed).

**Boot**: `BootReceiver` exists purely to make the framework load the package on
`BOOT_COMPLETED` (no real work; just a log line) so the manifest SMS receiver is warm before the
first message.

**Persistence**: everything is in a single `SharedPreferences` file named `mc_sms_fwd_wa`. No
database. Lists (senders, regexes) are newline-delimited strings. Logs use a
`timestamp\x1Fmessage` format with auto-pruning (35 days / 2000 entries); `LogUtils.addToLog`
collapses CR/LF/`\x1F` runs in the message to a space so multi-line bodies can't corrupt the
line-oriented format. WhatsApp credentials live under keys defined in `WhatsAppConfig`:
`waPhoneNumberId`, `waRecipient`, `waEnabled` (default true). The template is fixed in code (see
the WhatsApp channel above), so there are no template prefs. Telegram: `tgEnabled` (default
false), `tgChatId` (`TelegramConfig`). SMS: `smsEnabled` (default false) and
`forwardTo` — the destination number (`SmsConfig`). **Secrets (the WhatsApp access token `waAccessToken` and
the Telegram bot token `tgBotToken`) are NOT in this file** — `SecureStore` encrypts them with
AES/GCM using a key held by Android Keystore, then stores the ciphertext in the private
`mc_sms_fwd_secure` preferences file. Configs read tokens by calling
`SecureStore.read(context, …)`, so `WhatsAppConfig.load`/`TelegramConfig.load` take a `Context`
(not a `SharedPreferences`).

**Screens** are Compose, each backed by an `AndroidViewModel`. Edits mutate in-memory draft
`StateFlow`s and are persisted only when the user taps the screen's explicit **Save** button (no
debounced auto-save). On the Filters screen the allowed senders and message-format rules are each
rendered as a list of editable `OutlinedTextField` rows with a per-row delete button (order is not
significant); blank rows are dropped on save and ignored by the live pipeline.

## Conventions

- **Util objects** in `util/` are Kotlin `object` singletons (not classes). They take
  `SharedPreferences` or `Context` as parameters — no dependency injection. Current set:
  `SenderListStore`, `RegexListStore`, `SenderMatcher`, `TextNormalizer`, `ForwardTemplate`,
  `ForwardStatsStore`, `WhatsAppConfig`, `WhatsAppCloudChannel`, `TelegramConfig`,
  `TelegramChannel`, `SmsConfig`, `SmsChannel`, `SecureStore`, `LogUtils`.
- **Edge-to-edge** is enabled once in `MainActivity.onCreate` via `enableEdgeToEdge()`; Compose
  `Scaffold` + window-inset padding handle the rest per screen.
- **Regex matching** uses `TextNormalizer.normalizeForMatching` (NFD + strip combining marks +
  lowercase) so patterns can be written accent-free and case-free. Invalid regexes are
  silently treated as non-matches.
- **Version catalog** (`gradle/libs.versions.toml`) manages all dependency and SDK versions;
  `app/build.gradle.kts` references them via `libs.*`.
- Release signing is opt-in via Gradle properties (`RELEASE_KEYSTORE_PATH`, etc.). No keystore
  or access token is committed to the repository.
- The WhatsApp access token and Telegram bot token are stored **encrypted at rest** via
  `SecureStore` (AES/GCM with an Android Keystore-held key), never in the plaintext `mc_sms_fwd_wa` prefs —
  never write them to logs, never include them in error messages, never paste them into bug
  reports. In the Settings UI the token fields are **write-only**: the saved value is never
  re-displayed (the field loads with a bullet mask); typing replaces the stored token, while
  leaving the mask untouched keeps the existing one. The SMS channel has no secret (it uses the
  device modem).
- **`FiltersViewModel.runTest` is a dry-run mirror of the live pipeline.** The Filters screen has
  an inline "Test a message" card (sample sender + message) that subjects the input to the
  **currently displayed (possibly unsaved) draft** filters — draft senders, draft rules (match any,
  invalid patterns skipped), draft template — and the same channels `SmsReceiver` does (all three,
  via each config's `isOperational`). The last-used sender/message are persisted (`lastTestSender`,
  `lastTestMessage`); the sender otherwise defaults to the first phone-like entry in the senders
  list. If you add or change a channel or the matching logic, update `runTest` so the two cannot
  drift.
