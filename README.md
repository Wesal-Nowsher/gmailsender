# Gmail Sender

Android sender for separate emails through Gmail screen automation.

## Use

1. Install the debug APK from the latest successful Build APK Actions run.
2. In Sending account, enter the exact Gmail address. Open Gmail and select that account.
3. In Recipients, paste one email per line or import TXT/CSV. The app shows invalid entries and unique recipients.
4. Enter the email subject and message under Write email. Draft fields are saved locally.
5. Enable Gmail Sender in Android Accessibility settings, review the queue, and start sending.
6. Keep Gmail visible and the phone unlocked. Check Sending progress if the app pauses.

The service checks visible account, recipient, subject and body before tapping Send. It advances only after observing Gmail's send confirmation. A send confirmation does not prove delivery to the recipient. Uncertain sends require checking Gmail Sent before retrying.

## Current compatibility

English Gmail controls are supported. Gmail interface differences or hidden fields can cause the app to pause. Choose the sending account manually in Gmail before starting; account switching is not automated. No Gmail API credentials or Gmail passwords are stored. Gmail sending limits apply.

## Build

GitHub Actions installs Gradle 8.9 and Android SDK 35, builds assembleDebug and uploads gmail-sender-debug-apk. This is a debug build for personal installation. Device behavior requires validation on the target phone.
