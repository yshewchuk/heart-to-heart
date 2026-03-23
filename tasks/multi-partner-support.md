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

### Pairing Instance UID Model

Each QR code contains a **fresh UUID (`instanceUid`) generated at QR creation time** — not the user's Firebase UID. This means:
- The same user can generate unlimited unique QR codes for different pairing attempts
- No Firestore document cleanup needed — the instance document itself enforces one-time use
- Multiple people can simultaneously scan the same user's QR at an event without collision

### Firestore Schema

```
users/{uid}
  fcmToken: string

users/{uid}/pairingInstances/{instanceUid}   ← NEW: created per QR generation
  status: "active" | "used"      // "used" = already paired, reject reuse
  encryptionKey: string           // Alice's encryption key for this instance
  createdAt: timestamp

users/{uid}/partners/{pairingId}   ← pairingId = instanceUid from acceptance
  partnerUid: string
  partnerFcmToken: string
  displayName: string
  pairedAt: timestamp
  encryptionKey: string            // our key for sending TO them

users/{uid}/pairingRequests/{requesterUid}
  requesterUid: string
  requesterFcmToken: string
  requesterEncryptionKey: string
  instanceUid: string              // which pairingInstance this targets
  status: "pending" | "accepted" | "rejected"
```

### One-Time QR Enforcement

When Alice generates a QR code:
1. Create `pairingInstances/{instanceUid}` with `status: "active"`
2. Embed `instanceUid` in QR deep link alongside her Firebase UID

When Bob sends a pairing request:
- The request targets Alice's `instanceUid`
- Alice's app checks `pairingInstances/{instanceUid}/status == "active"` before accepting

After successful pairing:
- `pairingInstances/{instanceUid}` is updated to `status: "used"`
- Both parties write to their `partners/{pairingId}` using `instanceUid` as the key

If a third party (Mallory) tries to reuse Alice's QR:
- Firestore lookup finds `status: "used"` → request rejected

### QR Code Format

```
heart-to-heart://pair?uid=ALICE_UID&instance=INSTANCE_UID&key=KEY_A
```

- `ALICE_UID` — Alice's Firebase UID (stable identity)
- `INSTANCE_UID` — Fresh UUID generated per QR (one-time use)
- `KEY_A` — Alice's encryption key for this instance

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
  - Description: Add `pairingId`/`instanceUid` to Partner model, add `pairingInstances/{instanceUid}` Firestore subcollection for one-time QR enforcement, redesign DataStore schemas to Map<pairingId, T>, update Firestore `partners` subcollection to use instanceUid as key, generate fresh UUID per QR code
  - Est: ~5 files, ~450 lines
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
