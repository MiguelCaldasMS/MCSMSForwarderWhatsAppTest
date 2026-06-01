# MC SMS → WhatsApp / Telegram Test

Sister project to **MC SMS Forwarder**. Listens for incoming SMS on an Android device, runs them through the same sender/regex filter pipeline, and forwards the matched body to **WhatsApp** (via the [WhatsApp Cloud API](https://developers.facebook.com/docs/whatsapp/cloud-api/)) and/or **Telegram** (via the [Telegram Bot API](https://core.telegram.org/bots/api)). Both outbound channels are independently toggleable; you can enable one, the other, or both at once.

> **Test variant.** All credentials (WhatsApp access token, Telegram bot token) are stored as plain text in the app's private `SharedPreferences`. Only install on a device you fully control, and use the narrowest credentials you can.

## What it does

- `BroadcastReceiver` listens to `SMS_RECEIVED`.
- Reassembles multipart messages.
- Drops everything unless **master switch** is on.
- Matches the sender against the **allowed senders** list (E.164 phone numbers via `PhoneNumberUtils.areSamePhoneNumber`, or case-insensitive exact match for alphanumeric IDs).
- Normalizes the body (NFD + strip combining marks + lowercase) and matches it against **any** of the configured regex patterns.
- Optionally re-formats the outgoing text with a template (`%s` = source, `%t` = time, `%m` = original message).
- Sends the result through **every enabled channel**:
  - **WhatsApp** — `POST https://graph.facebook.com/v21.0/{PHONE_NUMBER_ID}/messages` with a `Bearer` token, as either a free-form `text` message (inside the customer 24‑hour window) or an **approved template** with the SMS body bound to a single body parameter.
  - **Telegram** — `POST https://api.telegram.org/bot{TOKEN}/sendMessage` with `chat_id` + `text` (web previews disabled).

The reception, filtering, normalization, multipart handling, and template logic are intentionally **byte-for-byte** identical to the upstream SMS forwarder. Only the outbound channels changed.

## What is NOT included

- No retry / backoff queue (SMS provider does that for the upstream project; the Cloud API and Bot API both require app-side handling and that's out of scope for this test variant).
- No webhook server for delivery receipts.
- No media (image/audio/document) forwarding — text only.
- No loop-guard: SMS arriving on your phone can never re-trigger a WhatsApp or Telegram send back to that same SMS sender, so the guard from the upstream project is gone.

## Build & install

```powershell
.\gradlew.bat :app:assembleDebug          # build debug APK
.\gradlew.bat :app:installDebug           # build + install on connected device/emulator
```

`minSdk` 33, `targetSdk` 36, Kotlin 2.0, AGP 8.13, Material 3.

Release signing is opt-in via Gradle properties (`RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). No keystore is committed.

## One-time Meta setup (WhatsApp)

The quickest and cheapest path is to use the **test phone number** that Meta provisions for you for free. No credit card, no Business Verification, no template approval cycle. Recipient list is capped at 5 numbers — enough for personal forwarding.

### Click-path: free test number with a never-expiring token

1. Sign in at <https://developers.facebook.com/> and create a **Business** app. From the app dashboard, add the **WhatsApp** product.
2. *WhatsApp → API Setup*: Meta auto-provisions a **WhatsApp Business Account (WABA)** and a **test phone number**. Copy the **Phone number ID** (numeric, under the test number).
3. Still in *API Setup*, under **To**, click **Manage phone number list** and add **your** WhatsApp number as a recipient. Meta will send you a 6-digit verification code on WhatsApp — enter it. You can add up to 5 recipients total.
4. (Optional but recommended) **Send a test message** from the *API Setup* page using the default `hello_world` template, just to confirm the WABA is healthy before generating the long-lived token.
5. Create the long-lived token:
   1. Open **Meta Business Suite** → the gear icon (Business settings) for the business that owns the WABA.
   2. *Users → System users → Add* — give it a name (e.g. `sms-forwarder`) and role **Admin**.
   3. With the new system user selected, click **Add assets** → **Apps** → pick your app → toggle **Full control**. Repeat **Add assets** → **WhatsApp accounts** → pick your WABA → toggle **Full control**.
   4. Click **Generate new token** → pick your app → **Token expiration: Never** → select scopes `whatsapp_business_messaging` and `whatsapp_business_management` → **Generate token** → copy it once (you cannot view it again).
6. In this app's Settings: paste **Phone Number ID**, **Access token**, and **Recipient** (E.164, e.g. `+3519...`). Leave **Send as approved template** ON and use the prebuilt `hello_world` / `en_US` for the very first ping, then either switch templates or turn the toggle off and rely on the 24-hour service window.

Notes:
- The test number itself is permanent for the life of the WABA — do **not** delete it from *API Setup*.
- Recipient numbers must be **opted-in** (the 6-digit confirmation flow above does that). Adding new ones requires the same confirmation.
- Free tier limit is intentionally 5 destinations and 250 conversations/day — plenty for personal SMS forwarding.
- Outside the 24-hour customer service window, only **approved templates** are allowed. The `hello_world` template ships pre-approved for every WABA.

### Production phone number (optional)

If you need to send from your real number instead of the test number, you'll have to **migrate or onboard a real phone number** (involves Business Verification, two-step PIN, possibly Embedded Signup). That's out of scope for this test variant.

## One-time Telegram setup

1. Open Telegram and start a chat with [**@BotFather**](https://t.me/BotFather).
2. Send `/newbot`. Pick a display name and a username ending in `bot` (e.g. `mc_sms_relay_bot`).
3. BotFather replies with an **HTTP API token** of the form `123456789:ABCdefGhI...`. Copy it.
4. From your **personal** Telegram account, send any message to the new bot (a `/start` is fine). The bot has to receive at least one message before it can find your chat ID.
5. Get your **chat ID**:
   - Quick way: chat with [**@userinfobot**](https://t.me/userinfobot) — it replies with your numeric user ID, which is also your bot's `chat_id` for direct messages.
   - Or, in a browser: `https://api.telegram.org/bot<TOKEN>/getUpdates` and look for `"chat":{"id":...}` in the JSON.
   - For a group, add the bot to the group, send a message, then call `getUpdates` — group IDs are negative numbers (e.g. `-1001234567890`).
6. In this app's Settings, expand the **Telegram Bot API** section, paste the **Bot token** and **Chat ID**, then flip **Forward to Telegram** on. Use the **Send Telegram test message** button to confirm.

Notes:
- Telegram bots can DM only users who have started the bot at least once — same reason as step 4.
- There are no template approvals, no 24-hour windows, no recipient lists — your bot can send anything to its allowed chats indefinitely.
- The bot token grants full control of the bot. Treat it like a password.

## In-app configuration

Settings screen:

**Filtering (shared by all channels)**

- **Allowed senders** — one per row; phone numbers or alphanumeric IDs.
- **Message format regexes** — one per row; a message is forwarded if **any** pattern matches.
- **Forwarding template** (optional) — `%s`, `%t`, `%m` tokens.

**WhatsApp Cloud API**

- **Forward to WhatsApp** — master toggle for the channel.
- **Phone Number ID** — numeric, from Meta.
- **Access token** — Bearer token; stored as plain text on device (see warning above).
- **Recipient** — destination WhatsApp number in E.164 form (`+35191XXXXXXX`).
- **Send as approved template** — on by default. When on, fill **Template name** and **Template language** (e.g. `en_US`). When off, messages are sent as free-form `text` (only works inside the 24-hour customer service window).
- **Send WhatsApp test message** button — POSTs a synthetic message to your recipient using your current WhatsApp settings.

**Telegram Bot API**

- **Forward to Telegram** — master toggle for the channel (off by default).
- **Bot token** — from @BotFather; stored as plain text.
- **Chat ID** — numeric (positive for DMs, negative for groups).
- **Send Telegram test message** button — POSTs a synthetic message to the chat ID using your current Telegram settings.

A SMS is forwarded to **every channel whose toggle is on and whose credentials are complete**. Each successful or failed delivery is logged separately. The activity stats counter increments **once per matched SMS**, regardless of how many channels accepted it.

## Architecture

Single-module Android app (`:app`), Kotlin, no Compose — XML layouts with Material 3.

**Pipeline** (`SmsReceiver`): incoming SMS → master kill-switch (`mc_sms_fwd_wa`/`master_enabled`, default ON) → bail if no channel is operational (enabled toggle on AND credentials present) → reassemble multipart → match sender via `SenderMatcher` → normalize body via `TextNormalizer.normalizeForMatching` → compile each regex once and match any → apply optional `ForwardTemplate` → `BroadcastReceiver.goAsync()` → fan out the same body to **every operational channel** in parallel. A shared `AtomicInteger` counts pending channel callbacks; once they all complete, the receiver records exactly one stat (if any channel succeeded) and calls `pending.finish()`.

`WhatsAppCloudChannel` and `TelegramChannel` are sibling singletons. Each uses `HttpURLConnection` on its own single-thread daemon executor (`wa-sender`, `tg-sender`), 10 s connect / 20 s read. Each reports its own `SEND OK [WhatsApp]` / `SEND OK [Telegram]` (or `SEND FAILED`) entry to the activity log with the HTTP status and provider-specific error summary (Meta `error.{code,type,message}` for WhatsApp, Telegram `error_code` + `description` for Telegram). Neither channel ever logs its bearer/bot token.

## License

MIT — see [LICENSE](LICENSE).
