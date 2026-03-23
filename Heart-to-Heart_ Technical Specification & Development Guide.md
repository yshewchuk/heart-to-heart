# **Heart-to-Heart: Technical Specification for a High-Priority Relationship Messenger**

## **1\. Executive Summary and Strategic Alignment**

### **1.1 Project Overview**

"Heart-to-Heart" is a specialized Android application designed to bridge the "Latency Gap" in high-trust relationships. While modern smartphones aggressively filter notifications to reduce noise, they often inadvertently silence partners during urgent moments. Heart-to-Heart creates a privileged communication tunnel, utilizing Android's STREAM\_ALARM audio channel to bypass Silent Mode and Do Not Disturb (DND) settings for critical messages.

The app adheres to a "Cute-Utility" philosophy: while the backend utilizes aggressive system permissions typically reserved for critical alarms, the frontend presents a soft, connection-focused interface to reduce the psychological barrier of granting such access.

### **1.2 Core Value Proposition**

* **The "Break-Glass" Connection:** Ensures that messages from a specific loved one are physically felt and heard, regardless of the phone's volume settings.  
* **Context-Aware Escalation:** Not every message is an emergency. The system differentiates between a gentle "Thinking of You" vibration and a "Pick Up Now" alarm.  
* **Serverless Efficiency:** Built on a "Cloudless" architecture using Google Firebase to maintain near-zero infrastructure costs while ensuring high reliability.

## ---

**2\. Feature Specification: Notification Categories**

A core requirement is the ability to send different *types* of signals. The app does not treat all interactions as alarms. Each notification category includes support for a **custom plain-text note** (e.g., "Bring milk," "I'm locked out," "Miss you").

### **2.1 Category Hierarchy**

| Category | Icon | Sound Behavior | Intensity | Use Case |
| :---- | :---- | :---- | :---- | :---- |
| **Flutter** | 🦋 | **Silent / Vibrate Only.** Respects system silence. Gentle, short vibration pattern. | Low | "Thinking of you," "Good luck today." |
| **Nudge** | 👋 | **Standard Notification.** Plays the default notification sound. Respects Silent mode but bypasses "Focus" filters if whitelisted. | Medium | "Dinner is ready," "Check your texts." |
| **Heartbeat** | ❤️ | **Bypass Silent Mode.** Plays a custom, rhythmic "heartbeat" sound at 50% volume. | High | "Call me when you can," "Important update." |
| **Lifeline** | 🚨 | **Critical Alarm.** Bypasses Silent & DND. Ramps volume from 10% to 100% over 30 seconds. Loops until acknowledged. | Critical | "Emergency," "Pick up now," "I need help." |

### **2.2 The "Note" Payload**

Every message type supports an optional text payload.

* **UI Implementation:** When selecting a category, a simple text input field appears (max 140 chars).  
* **Notification Appearance:** The text note is displayed as the setContentText (body) of the notification. For "Lifeline" alarms, this text is displayed in large font on the full-screen wake-up activity.

## ---

**3\. Development Toolkit: Getting Started**

To build this MVP efficiently, you will need the following tools. This stack is optimized for Android-native development with a serverless backend.

### **3.1 Required Software**

* **Android Studio (Ladybug or Koala Feature Drop):** The official IDE. You will need the latest SDK tools to compile for Android 14 (API 34\) and preview Android 15 features.  
* **Java Development Kit (JDK) 17 or 21:** Required for modern Android builds.  
* **Node.js (v18 or v20 LTS):** Required to write and deploy Firebase Cloud Functions.  
* **Git:** For version control.

### **3.2 Accounts & Services**

* **Google Play Console Account:** ($25 one-time fee). Required to publish the app and, critically, to request the USE\_FULL\_SCREEN\_INTENT permission exemption for "Alarm" apps.  
* **Firebase Account:** (Free "Spark" plan). Handles authentication, database, and push notifications.

### **3.3 Key Libraries & Dependencies**

Add these to your build.gradle.kts:

1. **Language:** Kotlin (Standard for modern Android).  
2. **UI Framework:** Jetpack Compose (For building the "cute" UI quickly with animations).  
3. **Backend Integration:**  
   * com.google.firebase:firebase-bom (Bill of Materials).  
   * com.google.firebase:firebase-messaging (FCM for push signals).  
   * com.google.firebase:firebase-firestore (For pairing/handshakes).  
   * com.google.firebase:firebase-auth (For anonymous login).  
4. **QR Code Scanning:** com.google.mlkit:barcode-scanning (Google's on-device ML kit is faster and lighter than ZXing).  
5. **Background Work:** androidx.work:work-runtime-ktx (WorkManager for reliability).

## ---

**4\. Technical Architecture**

### **4.1 Infrastructure: The "Push-Pull" Hybrid**

We utilize a Serverless architecture to keep costs low.

1. **Identity:** Users sign in via **Firebase Auth (Anonymous)**. This generates a UID without requiring email/password, reducing friction.  
2. **Signaling:** **Cloud Firestore** is used *only* for the pairing process (exchanging IDs and keys).  
3. **Transport:** **Firebase Cloud Messaging (FCM)** delivers the actual alerts. The message content (including the text note) is sent in the FCM data payload, meaning we do not need to store private messages in a database.

### **4.2 Security: The Pairing Flow**

Since we avoid a searchable user database for privacy, pairing must be explicit and physical.

* **Step 1:** User A generates a QR code containing heart-to-heart://pair?uid=USER\_A\_ID.  
* **Step 2:** User B scans this code using the app.  
* **Step 3:** User B's app writes their own UID and FCM\_TOKEN to a private sub-collection in User A's Firestore document.  
* **Step 4:** User A's app listens for this write, validates it, and saves User B as a contact.  
* **Encryption:** For added security, a shared secret key can be generated during the QR scan to encrypt the text notes end-to-end (E2EE) before sending them via FCM.

## ---

**5\. Deep Dive: Audio & Notification Logic**

### **5.1 Bypassing Silent Mode**

To play sound when the phone is muted, we must use the STREAM\_ALARM channel. Standard notifications use STREAM\_NOTIFICATION, which is suppressed by silent mode.

**Code Implementation:**

Kotlin

val audioAttributes \= AudioAttributes.Builder()  
  .setUsage(AudioAttributes.USAGE\_ALARM) // Key: Identifies as an alarm  
  .setContentType(AudioAttributes.CONTENT\_TYPE\_SONIFICATION)  
  .build()

val mediaPlayer \= MediaPlayer().apply {  
    setAudioAttributes(audioAttributes)  
    setDataSource(context, soundUri)  
    prepare()  
}  
// Even if Ringer Mode is VIBRATE, this will play audibly.  
mediaPlayer.start() 

### **5.2 Bypassing "Do Not Disturb" (DND)**

DND is stricter than Silent Mode. To bypass it, the app needs two things:

1. **Notification Channel Configuration:** The channel must be set to IMPORTANCE\_MAX.  
2. **Audio Focus:** The USAGE\_ALARM attribute generally bypasses DND on most devices.  
3. **Permission Fallback:** If the system is strictly enforcing DND, the app may prompt the user to grant "Override Do Not Disturb" permission for the specific "Heart-to-Heart" notification channel in system settings.

### **5.3 Volume Escalation Algorithm**

For the "Lifeline" category, we need to ramp up the volume to avoid startling the user too aggressively while ensuring they wake up.

**Logic:**

1. **Capture Initial State:** Save the current STREAM\_ALARM volume level.  
2. **Set Floor:** Set volume to 10%.  
3. **Loop:** Every 3 seconds, increase volume by 10%.  
4. **Cap:** Stop at 100%.  
5. **Safety:** If the screen is turned on or the phone is moved (accelerometer check), stop ramping immediately.

## ---

**6\. Android 14/15 Compliance**

### **6.1 Full Screen Intent (FSI) Restrictions**

Android 14 restricts USE\_FULL\_SCREEN\_INTENT (the ability to launch an activity directly over the lock screen) to "Calling" and "Alarm" apps.

* **Strategy:** You must categorize "Heart-to-Heart" as a **"Lifestyle / Alarm"** app in the Google Play Store.  
* **Permission:** Declare \<uses-permission android:name="android.permission.USE\_FULL\_SCREEN\_INTENT" /\>.  
* **Runtime Check:** On Android 14+, check notificationManager.canUseFullScreenIntent(). If false, guide the user to settings to enable it manually.

### **6.2 Foreground Service Types**

To play continuous audio (looping alarm) while the app is in the background, you must use a Foreground Service.

* **Manifest Declaration:**  
  XML  
  \<service  
      android:name\=".services.AlarmService"  
      android:foregroundServiceType\="mediaPlayback" /\>

* **Note:** Using mediaPlayback requires the user to have started the action, which fits our "incoming message" trigger flow.

## ---

**7\. Diagram as Code**

### **7.1 System Architecture (Mermaid)**

Code snippet

graph TD  
    subgraph "User Device (Android)"  
        UI\[Jetpack Compose UI\]  
        LocalDB\[Local Preferences (Contacts)\]  
        Scan  
        Service  
        FCM\_Client  
    end

    subgraph "Google Cloud / Firebase"  
        Auth\[Firebase Auth (Anonymous)\]  
        Firestore\[Firestore (Pairing Handshake)\]  
        CloudFunc\[Node.js Cloud Function\]  
        FCM\_Server  
    end

    UI \--\>|1. Generate Pairing QR| Auth  
    UI \--\>|2. Scan QR| Scan  
    Scan \--\>|3. Send Handshake| Firestore  
      
    UI \--\>|4. Send 'Lifeline'| CloudFunc  
    CloudFunc \--\>|5. Validate & Dispatch| FCM\_Server  
    FCM\_Server \--\>|6. High Priority Push| FCM\_Client  
    FCM\_Client \--\>|7. Wake & Play Sound| Service  
    Service \--\>|8. Bypasses Silent Mode| UI

### **7.2 Emergency Message Sequence**

Code snippet

sequenceDiagram  
    participant Sender  
    participant Cloud as Firebase Cloud Function  
    participant Receiver as Receiver Device (Silent Mode)  
      
    Sender-\>\>Sender: Select "Lifeline" Category  
    Sender-\>\>Sender: Type note: "I'm locked out\!"  
    Sender-\>\>Cloud: POST /sendHeartbeat {type: "LIFELINE", note: "..."}  
      
    Note over Cloud: Validates Sender\<br/\>is authorized partner  
      
    Cloud-\>\>Receiver: FCM Push {priority: "high", data: {action: "ALARM"}}  
      
    Receiver-\>\>Receiver: WakeLock Acquire  
    Receiver-\>\>Receiver: Check DND Permissions  
      
    par Audio  
        Receiver-\>\>Receiver: Set Stream=ALARM  
        Receiver-\>\>Receiver: Start Volume Ramp (10% \-\> 100%)  
    and Visual  
        Receiver-\>\>Receiver: Launch FullScreenActivity  
        Receiver-\>\>Receiver: Display Note: "I'm locked out\!"  
    end  
      
    Receiver--\>\>Sender: Delivery Receipt (via FCM Upstream)

## ---

**8\. UI/UX Design Concepts**

### **8.1 Visual Identity**

* **Name:** Heart-to-Heart  
* **Iconography:** Two interlocking hearts or a heart with sound waves.  
* **Palette:** Warm, comforting colors (Coral \#FF6B6B, Soft White \#FAF9F6) to offset the "alarm" functionality.

### **8.2 The "Send" Screen**

A simple dashboard with the partner's avatar in the center.

* **Ring of Actions:** Surrounding the avatar are 4 distinct buttons for the categories (Flutter, Nudge, Heartbeat, Lifeline).  
* **Long Press:** Long-pressing the "Lifeline" button engages a 3-second countdown (to prevent accidental panic alarms).

### **8.3 The "Receive" Screen (Alarm State)**

* **Background:** Pulsing gradient animation (red/coral).  
* **Center:** The Sender's Profile Picture.  
* **Text:** Large, legible text displaying the Note (e.g., "I'm locked out\!").  
* **Action:** A "Slide to Acknowledge" bar (similar to answering a call) to stop the sound. Simple taps should *not* dismiss the alarm to ensure the user is actually awake/aware.