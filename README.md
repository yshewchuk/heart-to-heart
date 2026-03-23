# Heart-to-Heart 💕

A specialized Android messenger that creates a **privileged communication tunnel** for your most important person. Messages bypass Silent Mode and Do Not Disturb settings using Android's STREAM_ALARM audio channel.

## Core Concept

This app solves the "Latency Gap" problem - when modern smartphones filter out your partner's urgent messages. Heart-to-Heart ensures critical messages from your loved one are **always** heard.

## Features

### 4 Notification Categories

| Category | Emoji | Behavior | Use Case |
|----------|-------|----------|----------|
| **Flutter** | 🦋 | Silent, gentle vibration | "Thinking of you" |
| **Nudge** | 👋 | Standard notification | "Dinner is ready" |
| **Heartbeat** | ❤️ | Bypasses Silent Mode | "Call me when you can" |
| **Lifeline** | 🚨 | Bypasses DND, volume ramp | "Emergency!" |

### Key Technical Features

- **STREAM_ALARM audio** - Plays sound even in silent mode
- **Full-screen intent** - Shows over lock screen for Lifeline alerts  
- **Volume escalation** - Lifeline alerts ramp from 10% to 100% over 30 seconds
- **Firebase Cloud Messaging** - Serverless push notifications
- **QR code pairing** - No phone number or email required

## Getting Started

### Prerequisites

- Android Studio (Ladybug or newer)
- JDK 17 or 21
- Node.js v18+ (for Firebase Functions, optional)
- A Firebase project

### Setup

1. **Clone the repository**
   ```bash
   git clone <your-repo-url>
   cd BaeBuzz
   ```

2. **Configure Firebase**
   - Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com)
   - Add an Android app with package name `com.hearttoheart.app`
   - Download `google-services.json` and place it in `app/`
   - Enable Authentication (Anonymous) and Cloud Firestore

3. **Add audio files** (optional)
   - Place `heartbeat.mp3` and `alarm.mp3` in `app/src/main/res/raw/`
   - See `raw/README.md` for specifications
   - Without these, the app falls back to system alarm sounds

4. **Build and run**
   ```bash
   ./gradlew assembleDebug
   ```
   Or open in Android Studio and run directly.

### Testing the Proof of Concept

The MVP includes a **test mode** that works without pairing:

1. Launch the app
2. Select any category (Flutter, Nudge, Heartbeat)
3. Click "Send" to trigger a local alarm
4. For **Lifeline**: Hold the button for 3 seconds

**To verify silent mode bypass:**
1. Put your phone in Silent Mode or Do Not Disturb
2. Trigger a **Heartbeat** or **Lifeline** alert
3. Observe that the sound plays anyway!

## Project Structure

```
app/src/main/
├── java/com/hearttoheart/app/
│   ├── HeartToHeartApp.kt      # Application + notification channels
│   ├── MainActivity.kt          # Main entry point
│   ├── data/
│   │   └── MessageCategory.kt   # Data models
│   ├── services/
│   │   ├── AlarmService.kt      # Core alarm logic (STREAM_ALARM)
│   │   └── HeartFCMService.kt   # Firebase messaging handler
│   ├── receivers/
│   │   └── NotificationDismissReceiver.kt
│   └── ui/
│       ├── AlarmActivity.kt     # Full-screen alarm display
│       ├── theme/Theme.kt       # Compose theme
│       ├── components/          # Reusable UI components
│       └── screens/             # Main screens
└── res/
    ├── values/                  # Colors, strings, themes
    ├── drawable/                # Icons
    └── raw/                     # Audio files (add your own)
```

## Key Implementation Details

### Bypassing Silent Mode

```kotlin
val audioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ALARM)  // KEY: Uses alarm stream
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

val mediaPlayer = MediaPlayer().apply {
    setAudioAttributes(audioAttributes)
    // This will play even when phone is on silent!
}
```

### Volume Escalation (Lifeline)

The `AlarmService` implements a gradual volume ramp:
- Starts at 10% volume
- Increases by 10% every 3 seconds
- Caps at 100% after 30 seconds
- Respects original volume when dismissed

### Full-Screen Intent (Android 14+)

The app is designed to work with Android 14's FSI restrictions by:
- Declaring as an alarm-category app
- Using `setFullScreenIntent()` for lock screen display
- Gracefully falling back to heads-up notification if restricted

## Next Steps (Not Implemented)

- [ ] QR code pairing flow
- [ ] Firebase Cloud Functions for message delivery
- [ ] End-to-end encryption for notes
- [ ] Partner avatar/name customization
- [ ] Delivery receipts
- [ ] Settings screen (notification preferences)

## Permissions

| Permission | Purpose |
|------------|---------|
| `POST_NOTIFICATIONS` | Show notifications |
| `USE_FULL_SCREEN_INTENT` | Display over lock screen |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Play alarm in background |
| `ACCESS_NOTIFICATION_POLICY` | Bypass DND (optional) |
| `WAKE_LOCK` | Keep CPU awake during alarm |
| `CAMERA` | QR code scanning |

## License

MIT License - see LICENSE file

---

Built with ❤️ for the ones we love most.
