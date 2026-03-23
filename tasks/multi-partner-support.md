# Task: Multi-Partner Support

## Overview

Refactor Heart-to-Heart from a single-partner app to support up to 10 paired partners simultaneously. Each pairing is one-time (QR code cannot be reused), generates a unique encryption key, and persists independently with its own nickname, profile picture, and message history.

## Problem Statement

Currently `PairingRepository` stores exactly one partner. The app needs to support multiple simultaneous pairings so users can connect with more than one person (e.g., a partner on a work phone and personal phone treated as separate pairings).

## Scope

### In Scope
- Multiple simultaneous partners (max 10)
- Unique `pairingId` (UUID) per pairing instance — same Firebase UID can be paired multiple times as distinct entries
- One-time QR code use: pairing request document deleted from Firestore after acceptance
- Per-partner encryption key (new key per pairing instance)
- Local message history keyed by `pairingId`
- Partner selector UI on HomeScreen (horizontal strip or dropdown)
- "Pair with new user" button replaces single "Pair" button when ≥1 partner exists
- Unpair flow: deletes local data and removes partner subcollection entry in Firestore
- Cloud Functions validation: `targetPartnerUid` must be in sender's `partnerIds` list

### Out of Scope
- Forward secrecy / key rotation post-pairing
- Safety numbers / identity verification beyond physical QR exchange
- Push notification routing changes (FCM token-based routing is already correct)

## Affected Areas

- `app/src/main/java/com/hearttoheart/app/data/PairingRepository.kt` — multi-partner storage, one-time pairing, per-partner keys
- `app/src/main/java/com/hearttoheart/app/data/PartnerPreferencesRepository.kt` — keyed by `pairingId` instead of single partner
- `app/src/main/java/com/hearttoheart/app/data/MessageHistory.kt` — messages keyed by `pairingId`
- `app/src/main/java/com/hearttoheart/app/data/Partner.kt` — add `pairingId` field
- `app/src/main/java/com/hearttoheart/app/data/MessageSender.kt` — target specific partner by `pairingId`
- `app/src/main/java/com/hearttoheart/app/MainActivity.kt` — manage list of partners, selected partner state
- `app/src/main/java/com/hearttoheart/app/ui/screens/HomeScreen.kt` — partner selector, send to selected partner
- `app/src/main/java/com/hearttoheart/app/ui/screens/HistoryScreen.kt` — group/filter by partner
- `app/src/main/java/com/hearttoheart/app/ui/screens/SettingsScreen.kt` — per-partner settings, unpair
- `app/src/main/java/com/hearttoheart/app/services/HeartFCMService.kt` — route incoming messages to correct partner
- `functions/src/index.ts` — validate `targetPartnerUid` is in sender's partner list

## Architecture

### Pairing ID Model

```
pairingId = UUID generated at acceptance time (unique per pairing instance)
```

This solves the "same user on multiple devices" problem: the same Firebase UID can appear multiple times in a user's partner list, each with a different `pairingId` and nickname.

### Firestore Schema

```
users/{uid}
  fcmToken: string
  partnerIds: string[]  // list of pairingIds this user has paired with

users/{uid}/partners/{pairingId}
  partnerUid: string          // the other user's Firebase UID
  partnerFcmToken: string
  displayName: string
  pairedAt: timestamp
  encryptionKey: string        // our key for sending TO them

users/{uid}/pairingRequests/{pairingId}
  requesterUid: string
  requesterFcmToken: string
  requesterEncryptionKey: string
  status: "pending" | "accepted" | "rejected" | "paired"
  paired: bool  // true = already used, reject if true
```

After acceptance, the pairing request doc is deleted (one-time use).

### Local Storage Schema

DataStore keys are namespaced by `pairingId`:

```
PARTNERS: Map<pairingId, PartnerEntry>
MY_DECRYPTION_KEYS: Map<pairingId, string>   // pairingId → my decryption key
PARTNER_PREFS: Map<pairingId, PartnerPrefs>
MESSAGES: Map<pairingId, List<StoredMessage>>
```

## User Experience

### Zero Partners
HomeScreen shows "Get Started" prompt. Pair button reads "Pair with Partner".

### ≥1 Partner
HomeScreen shows horizontal partner selector strip (avatars/names). Pair button reads "Pair with New User". QR button is hidden when 10 partners reached.

### Sending a Message
User selects target partner from selector → selects category → optionally adds note → sends. Encryption uses that partner's specific key.

### Unpairing
Settings screen lists all partners with edit/unpair options. Unpair deletes local data + Firestore partner doc.

## Pull Requests

<br>

- [ ] **PR 1: Data layer redesign — models, local storage, Firestore schema**
  - Description: Add `pairingId` to Partner model, redesign DataStore schemas to Map<pairingId, T>, update Firestore to use `users/{uid}/partners/{pairingId}` subcollection, delete pairing request docs after acceptance
  - Est: ~5 files, ~400 lines
  - Status: Planned
  - Dependencies: None

<br>

- [ ] **PR 2: Multi-partner UI — HomeScreen selector, HistoryScreen, SettingsScreen**
  - Description: Add partner selector component to HomeScreen, update HistoryScreen to show per-partner history, add per-partner settings and unpair flow to SettingsScreen, update MainActivity to manage partner list
  - Est: ~5 files, ~500 lines
  - Status: Planned
  - Dependencies: PR 1

<br>

- [ ] **PR 3: Cloud Functions and FCM routing updates**
  - Description: Add `targetPartnerUid` to sendHeart request, validate sender has this partnerId in their list, route incoming FCM to correct pairingId in HeartFCMService
  - Est: ~2 files, ~100 lines
  - Status: Planned
  - Dependencies: PR 1

<br>

- [ ] **PR 4: README and cleanup**
  - Description: Update README to reflect new multi-partner architecture, add architecture diagram
  - Est: ~1 file, ~80 lines
  - Status: Planned
  - Dependencies: PRs 1–3

## Next Steps

1. Review and merge this plan
2. Implement PR 1 (data layer)
3. Implement PR 2 (UI layer)
4. Implement PR 3 (Cloud Functions + FCM routing)
5. Implement PR 4 (README)
6. Mark each PR complete in this document
