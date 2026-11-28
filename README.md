# Gmail Sender

Android app for sending one message to a long recipient list as **separate
individual emails**, from any of several Gmail accounts already signed in on the
phone. No recipient ever sees another recipient's address, and no CC or BCC is
used.

Sending goes through the official Gmail API, so the Gmail app is never driven or
automated, and no Gmail password is ever typed into this app or stored by it.

## What the owner does, day to day

1. Open the app, pick the business account in **Send this batch from**.
2. Paste the recipient list, or import a TXT/CSV file.
3. Type the subject and message.
4. **Review and send.** Sending continues in the background, so the phone can be
   locked and other apps used.

The **Progress** card shows sent / failed / waiting counts, names every failed
recipient with the reason, and offers **Pause**, **Resume remaining** and
**Retry failed only**. A recipient is marked sent only after Gmail accepts the
message, so resuming never sends anyone a duplicate.

## One-time setup

The app needs its own Google Cloud OAuth client, because Google only lets an app
send mail if that exact app build is registered. This takes about 20 minutes and
is done once.

### Step 1 - create the signing key

Google ties permission to the app's signing fingerprint, so every build must be
signed with the same key. Create it once and keep the file safe; losing it means
redoing Step 3.

```bash
keytool -genkeypair -v -keystore sender.jks -alias sender -keyalg RSA -keysize 2048 -validity 10000
```

Read the fingerprint:

```bash
keytool -list -v -keystore sender.jks -alias sender | grep SHA1
```

### Step 2 - store the key as GitHub secrets

```bash
base64 -w0 sender.jks
```

In the repository, under **Settings → Secrets and variables → Actions**, add:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the base64 output above |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `sender` |
| `KEY_PASSWORD` | the key password |

Without these the release APK builds unsigned and cannot be installed, and the
Build APK workflow says so in its log.

### Step 3 - register the app with Google

1. At [console.cloud.google.com](https://console.cloud.google.com), create a
   project.
2. **APIs & Services → Library →** enable **Gmail API**.
3. **APIs & Services → OAuth consent screen →** configure it, and add the scope
   `https://www.googleapis.com/auth/gmail.send`.
4. **Credentials → Create credentials → OAuth client ID → Android**, with:
   - Package name: `com.wykasolutions.gmailsender`
   - SHA-1: the fingerprint from Step 1

Which consent screen type to choose matters, and decides whether the owner has
to re-approve weekly:

- **The accounts are Google Workspace accounts in one organisation** - choose
  **Internal**. No Google review is needed and permission does not expire. This
  is the option to use if it is available.
- **The accounts are ordinary @gmail.com accounts** - the type is **External**,
  and the app starts in **Testing** status. Add each sending account under
  **Test users** (up to 100). It works immediately, but Google expires
  permission for unverified apps in Testing after **7 days**, so the owner has
  to tap Allow again about once a week. To stop that, submit the app for
  verification and set the status to **In production**; `gmail.send` is a
  sensitive scope, so it needs Google's review but not a paid security
  assessment.

The app handles the expiry safely either way: it pauses the batch, names the
account, and tells the owner exactly what to tap. Nothing is sent twice.

### Step 4 - install and add the accounts

1. Download `app-release.apk` from the latest **Build APK** run under the
   repository's **Actions** tab.
2. Install it on the phone, allowing install from unknown sources.
3. For each business Gmail: **Add sending account** → pick it from Android's
   list of accounts already signed in → **Allow**.

No password is typed. The accounts appear in the From dropdown and stay there.

## Sending limits

Google enforces these, not this app:

- Ordinary @gmail.com: about **500 recipients per day** per account.
- Google Workspace: about **2,000 per day** per account.

The app stops at **450 per account per day** to stay clear of Google's cut-off,
shows the count used, and pauses the batch with the remaining recipients still
queued so it can be resumed the next day or switched to another account. It also
leaves 1.5 seconds between messages and backs off when Gmail rate limits, so an
account is not flagged for bulk sending.

If the real need is more than a few hundred a day per business, Gmail is the
wrong tool and a sending service such as Amazon SES or SendGrid on the
business's own domain is the right one.

## Build locally

Requires JDK 17 or newer and the Android SDK (platform 35, build-tools 35.0.0).

```bash
ANDROID_HOME=/path/to/android-sdk gradle :app:assembleDebug
```

To build a signed release locally, set `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD` first.

## How it is put together

| File | Role |
| --- | --- |
| `MainActivity.kt` | the whole UI, built in code so there are no layout files |
| `Accounts.kt` | authorised sending accounts and the per-day sent counter |
| `GmailApi.kt` | OAuth token from Play services, and the Gmail send call |
| `Mime.kt` | builds the RFC 5322 message, UTF-8 safe |
| `Batch.kt` | the queue on disk, with per-recipient sent/failed/waiting state |
| `SendService.kt` | foreground service that works the queue, retries and paces |
