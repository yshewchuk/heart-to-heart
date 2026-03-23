# Task: Multi-Partner Support

## Overview

Refactor Heart-to-Heart to manage multiple Firebase anonymous auth accounts on a single device — one account per paired partner. Each pairing is a self-contained user+partner unit. The Firestore schema does not change.

## Problem Statement

Currently the app uses a single Firebase anonymous auth account for the entire device and supports exactly one partner. The app needs to support up to 10 simultaneous pairings — e.g., the same person pairing with "Alex (work phone)" and "Alex (personal phone)" as separate, independent pairings on the same device.

## Key Insight

The solution is not to change the database schema — it's to change the **auth model**: instead of one anonymous auth account per device, use **one anonymous auth account per pairing**. Each pairing on Alice's device is a separate Firebase user with its own UID, its own partner entry in Firestore, and its own encryption keys.

The Firestore schema already supports this — it was designed around Firebase UIDs, not device-level identity.

## Scope

### In Scope
- Multiple simultaneous pairings (max 10)
- Each pairing = new Firebase anonymous auth account created at QR generation time
- QR code contains the freshly-created anonymous UID (not the device's stable UID)
- One-time QR use: once an anonymous account is paired, its QR code is obsolete — the account can't generate a new pairing request
- Per-pairing message history (each Firebase user has its own messages subcollection)
- Partner selector UI on HomeScreen (horizontal strip or dropdown)
- "Pair with New User" button when ≥1 partner exists
- Unpair flow: deletes the anonymous account locally, removes partner doc from Firestore
- FCM routing: payload carries sender's anonymous UID so receiver knows which partner entry to use

### Out of Scope
- Forward secrecy / key rotation post-pairing
- Safety numbers / identity verification beyond physical QR exchange
- Firestore schema changes
- Cross-pairing message threads (each pairing is independent)

## Architecture

### Auth Model: One Anonymous Account Per Pairing

```
Device
├── UserAccount #1 (Firebase Anonymous UID A)
│   └── Paired with: Bob
│   └── Encryption key: key_AB
│   └── Message history: messages_A
├── UserAccount #2 (Firebase Anonymous UID B)
│   └── Paired with: Carol
│   └── Encryption key: key_BC
└── UserAccount #3 (Firebase Anonymous UID C)
    └── Paired with: Alex (work)
    └── Encryption key: key_AX
```

When the user taps "Pair with New User", the app creates a **new anonymous Firebase auth account**. The QR code embeds that new account's UID. Once that account completes pairing, it is never reused for another pairing — a future "Pair with New User" creates yet another fresh account.

### QR Code Format

```
heart-to-heart://pair?uid=ANONYMOUS_UID_A&key=KEY_A
```

- `ANONYMOUS_UID_A` — Fresh Firebase anonymous UID created at QR generation time
- `KEY_A` — Encryption key generated for this account

The QR does **not** contain the device's stable UID. This means an old QR is useless to anyone who scans it later — it points to an account that has already paired and cannot initiate new pairings.

### Firestore Schema (No Changes)

```
users/{uid}
  fcmToken: string

users/{uid}/partners/{partnerId}
  partnerUid: string
  partnerFcmToken: string
  displayName: string
  pairedAt: timestamp
  encryptionKey: string

users/{uid}/pairingRequests/{requesterUid}
  requesterUid: string
  requesterFcmToken: string
  requesterEncryptionKey: string
  status: "pending" | "accepted" | "rejected"
```

Each anonymous account has its own `partners` document. The schema is identical to today.

### Local Storage Schema (DataStore)

```
USER_ACCOUNTS: Map<anonymousUid, UserAccountEntry>
  where UserAccountEntry = { pairedPartnerUid?, pairedAt?, encryptionKey?, displayName? }

SELECTED_ACCOUNT_UID: string  // which account is currently active for sending
```

Each account entry is created at QR generation time (unpaired), and updated at pairing completion (with partner info).

### One-Time QR Enforcement

When "Pair with New User" is tapped:
1. `FirebaseAuth.signInAnonymously()` → new account with UID `A`
2. `PairingRepository.sendPairingRequest(A, targetUserId, key_A)`
3. Account `A` is saved to DataStore as "pending"

When a pairing request for account `A` is accepted:
1. `PairingRepository.completePairing(A, partner)` → saves partner info to account `A`'s local entry
2. Account `A` is now "paired"

If anyone re-scans the old QR for account `A`:
- The QR points to account `A`'s UID
- But account `A` already has a partner — `sendPairingRequest` for an already-paired account should be rejected (return error or no-op)
- The old QR is inert

### Message Sending

When the user selects a partner from the UI and sends:
1. Look up the active `UserAccount` for that partner
2. Call `MessageSender.sendMessage(account, partner, message)` using that account's UID and encryption key
3. The Cloud Function receives the message with `senderUid = account.UID`

### FCM Routing (HeartFCMService)

Incoming FCM payload already contains `senderUid`. The app:
1. Looks up which local account matches `senderUid`
2. Routes to the correct partner context (nickname, avatar, message history)

### Partner Selector UI

- Horizontal strip of avatars at top of HomeScreen
- Tapping an avatar selects that account → all send actions target that partner
- Selected account highlighted
- Badge or label shows partner nickname

## Affected Areas

- `app/src/main/java/com/hearttoheart/app/data/PairingRepository.kt` — create new anonymous account per pairing, manage per-account pairing state, check if account already paired
- `app/src/main/java/com/hearttoheart/app/data/PartnerPreferencesRepository.kt` — keyed by anonymous UID
- `app/src/main/java/com/hearttoheart/app/data/MessageHistory.kt` — keyed by anonymous UID (each account has its own messages)
- `app/src/main/java/com/hearttoheart/app/data/MessageSender.kt` — send using specific account UID
- `app/src/main/java/com/hearttoheart/app/MainActivity.kt` — manage list of accounts, selected account state
- `app/src/main/java/com/hearttoheart/app/ui/screens/HomeScreen.kt` — partner selector strip
- `app/src/main/java/com/hearttoheart/app/ui/screens/HistoryScreen.kt` — filter by selected account
- `app/src/main/java/com/hearttoheart/app/ui/screens/SettingsScreen.kt` — per-account settings, unpair
- `app/src/main/java/com/hearttoheart/app/ui/screens/ShowQRScreen.kt` — generate new anonymous account at QR creation time
- `app/src/main/java/com/hearttoheart/app/ui/screens/ScanQRScreen.kt` — send pairing request from the anonymous account created at QR scan time
- `app/src/main/java/com/hearttoheart/app/services/HeartFCMService.kt` — route incoming messages to correct account/partner
- `functions/src/index.ts` — no changes needed (already validates sender UID matches auth)

## User Experience

### Zero Partners
HomeScreen shows "Get Started" prompt. Pair button reads "Pair with Partner".

### ≥1 Partner
HomeScreen shows horizontal partner selector strip. Pair button reads "Pair with New User". Button hidden when 10 partners reached.

### Sending a Message
Select target partner from strip → select category → optionally add note → send. Each partner has their own encryption key and message history.

### Unpairing
Settings screen lists all accounts with partner name, nickname, and unpair option. Unpair deletes local account data (including all its messages).

## Pull Requests

<br>

- [ ] **PR 1: Auth model — new anonymous account per pairing**
  - Description: Change PairingRepository to create a fresh Firebase anonymous account at QR generation time, not reuse a device-level account. QR contains the new account's UID. Save accounts in DataStore as a Map keyed by anonymous UID. Add check to reject pairing requests from already-paired accounts. Update ShowQRScreen and ScanQRScreen to use the new account-based flow.
  - Est: ~5 files, ~350 lines
  - Status: Planned
  - Dependencies: None

<br>

- [ ] **PR 2: Multi-partner UI — HomeScreen selector, HistoryScreen, SettingsScreen**
  - Description: Add partner/account selector strip to HomeScreen. Update MainActivity to hold selected account UID state and list of accounts. Update HistoryScreen to show messages for selected account. Add per-account settings and unpair flow to SettingsScreen. Update HeartFCMService to route incoming messages to the correct account.
  - Est: ~5 files, ~500 lines
  - Status: Planned
  - Dependencies: PR 1

<br>

- [ ] **PR 3: README and cleanup**
  - Description: Update README to reflect new multi-partner architecture. Update "Next Steps" checklist to reflect completed features.
  - Est: ~1 file, ~80 lines
  - Status: Planned
  - Dependencies: PRs 1–2

## Next Steps

1. Review and merge this plan
2. Implement PR 1 (auth model)
3. Implement PR 2 (UI layer)
4. Implement PR 3 (README)
5. Mark each PR complete in this document
