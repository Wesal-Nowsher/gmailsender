# Gmail Sender

Android app that works the Gmail app for you: one message, sent as **separate
individual emails** to a whole recipient list, from whichever of the business
Gmail accounts on the phone you assign to each person.

Nobody is CC'd or BCC'd. Each recipient gets an ordinary one-to-one email and
sees only their own address.

There is no Google Cloud project, no API key, no app password and no
verification. The app taps inside Gmail the way a person would, using the
accounts already signed into the Gmail app.

## How it is used

1. **Your Gmail accounts** - add each business Gmail once. Android lists the
   accounts already signed in on the phone; pick them from that list.
2. **Recipients** - paste one address per line, or import a TXT/CSV file.
3. **Write the email** - subject and message, typed once.
4. **Who sends to whom** - tap **Build the queue**, then either:
   - send everything from one account,
   - split the list evenly across several accounts (useful for daily limits), or
   - open **Choose account per recipient** and set them one by one.
5. **Run the bot** - turn it on in Accessibility settings once, then tap
   **Start sending**. Leave the phone unlocked and do not touch it while it
   works.

## What the bot actually does, per recipient

1. Opens Gmail's compose screen with the address, subject and message filled in.
2. Reads which account the message would go out from.
3. If that is not the account assigned to this recipient, taps the From row and
   picks the right one.
4. Checks the recipient, the account, the subject and the message on screen all
   match the queue.
5. Taps **Send**, then waits until Gmail itself confirms.
6. Pauses about 2.5 seconds, then moves to the next person.

**It will not tap Send until every one of those checks passes.** A screen it
cannot read stops the run instead of sending the wrong message to the wrong
person from the wrong account.

## When something does not match

A send is only counted once Gmail confirms it, so pausing and resuming never
sends a duplicate.

If the bot taps Send but never sees a confirmation, it stops and marks that one
recipient **uncertain** rather than guessing. The app asks you to look in that
account's Sent folder and say what actually happened - because guessing wrong
either skips someone or mails them twice.

**Progress** shows sent / failed / waiting, lists every failure with its reason,
and offers Pause, Resume, and Retry failed only.

## If the bot gets stuck on your phone

Gmail's screen differs between versions, languages and phones. When the bot
cannot find something, it stops and records what it saw.

Tap **Save diagnostics** - it shows what the bot could see on the Gmail screen
and lets you share the file. That file is what makes a mis-targeted tap fixable
exactly, instead of by guesswork. Send it over and the targeting can be
corrected.

Known limits today:

- Gmail's controls are matched in **English**. Another Gmail language needs the
  labels added.
- The sending account must already be signed into the **Gmail app** itself, not
  just added to Android.
- The phone must stay **unlocked with Gmail on screen** while the bot runs. It
  cannot work in the background - that is the trade-off for not using the API.
- Roughly **2.5 seconds per email**, so about 300 emails takes 15-20 minutes of
  the phone being left alone.

## Sending limits

Google enforces these, not this app:

- Ordinary @gmail.com: about **500 per day** per account.
- Google Workspace: about **2,000 per day** per account.

The app stops at **450 per account per day**, shows each account's count, and
pauses with the rest still queued so they can go out tomorrow or be reassigned
to another account. Splitting one list across several accounts is what the
**Split evenly** button is for.

If a business needs more than a few hundred a day, Gmail is the wrong tool and a
sending service such as Amazon SES or SendGrid on that business's own domain is
the right one.

## Install

Download `app-debug.apk` from the latest **Build APK** run under the
repository's **Actions** tab, copy it to the phone, and install it allowing
unknown sources. Then turn the bot on under
**Settings → Accessibility → Gmail Sender**.

Signing the release build is optional and only matters for distributing a proper
release APK. To do it, create a keystore and set the `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` repository secrets; the
workflow picks them up automatically.

## Build locally

Needs JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0). No
third-party dependencies - the account picker and the accessibility APIs are
both part of Android.

```bash
ANDROID_HOME=/path/to/android-sdk gradle :app:assembleDebug
```

## How it is put together

| File | Role |
| --- | --- |
| `MainActivity.kt` | accounts, recipients, message, queue and progress |
| `AssignActivity.kt` | which account sends to which recipient |
| `Senders.kt` | the business accounts and each one's daily count |
| `Batch.kt` | the queue on disk: recipient, account, sent/failed/waiting |
| `Screen.kt` | reads and taps Gmail's screen by what a person would see |
| `GmailBot.kt` | the accessibility service that drives Gmail |
| `Diagnostics.kt` | records what the bot saw, for fixing a stuck step |
