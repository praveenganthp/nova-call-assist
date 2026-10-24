# Nova — Free Offline Android Voice Calling Assistant

Nova is a fully native Android voice assistant designed to let users search local contacts and initiate phone calls entirely hands-free and offline.

Nova operates **strictly without paid APIs, without cloud speech streaming, without subscriptions, and with zero external network dependencies**.

---

## 1. Key Features

- **100% Offline Speech Recognition**: Powered by on-device OpenAI Whisper using high-performance `whisper.cpp` (MIT licensed) compiled natively via Android NDK with ARM NEON / OpenMP acceleration.
- **Superior Indian English Name Recognition**: Accurately transcribes diverse Indian names (e.g. *"Call Praveen"*, *"Call Vignesh"*, *"Call Karthik"*, *"Call Arun"*) without substituting random English dictionary words.
- **Wake-Word Detection**: Offline voice-gated keyword spotting for `"Hey Nova"` using continuous RMS audio energy gating (0% CPU during silence).
- **Diagnostics & Benchmark Dashboard**: Live measurement of model load time, transcription latency (ms), audio duration (s), Real-Time Factor (RTF), and configurable CPU inference thread counts (2-8).
- **Smart Contact Matching**:
  - Deterministic multi-tier matching: Exact match -> Prefix match -> Word overlap -> Substring match -> Levenshtein phonetic distance.
  - Automatic detection of ambiguous matches (e.g. user says *"Call Arun"* when both *"Arun Kumar"* and *"Arun Prakash"* exist). Prompts the user verbally and visually to select the right person.
  - Multiple phone numbers per contact (Mobile, Home, Work) handled seamlessly.
  - Graceful handling of contacts without phone numbers.
  - Number masking in UI (`•••• •• 4821`) for screen privacy.
- **Safe Call Initiation**:
  - **Default**: Android `Intent.ACTION_DIAL` (pre-populates the phone number in the dialer without placing the call automatically, requiring zero dangerous telephony permissions).
  - **Direct Calling (Configurable)**: `Intent.ACTION_CALL` when explicitly enabled by the user in Settings and granted `CALL_PHONE` permission.
  - **Confirmation Required**: Nova always announces the contact name and awaits verbal ("Yes") or tap confirmation before placing any call.
- **Offline Audio & Voice Feedback**:
  - Natural spoken prompts using Android's built-in offline `TextToSpeech`.
  - Immediate audio earcons / chimes using `ToneGenerator` for instant responsive feedback on wake-word detection, confirmation, and cancellation.
- **Modern Jetpack Compose UI**:
  - Dark Navy theme (`#0B111E`), electric blue accents (`#2563EB`, `#38BDF8`).
  - Animated pulsing concentric circular microphone indicator reflecting state in real time.
  - Live transcript display.
  - Contact confirmation card and candidate picker.
  - In-app typed fallback simulator for immediate end-to-end testing without speaking.
  - Full Settings screen for wake word, direct calling, TTS responses, model status, and battery guidance.

---

## 2. Architecture

Nova follows clean architecture principles with single-responsibility components and no heavyweight DI frameworks:

```
app/src/main/java/com/nova/callassist/
├── NovaApplication.kt              # App container & dependency root
├── MainActivity.kt                 # Compose Activity, lifecycle & permissions
├── ui/
│   ├── screens/
│   │   ├── MainScreen.kt           # Primary dashboard with animated mic & cards
│   │   └── SettingsScreen.kt       # Preferences, model status & battery guide
│   ├── components/
│   │   ├── MicPulseIndicator.kt    # Pulsing circular waveform animation
│   │   ├── ContactCard.kt          # Contact confirmation & candidate cards
│   │   └── StatusBadge.kt          # Dynamic assistant state indicator
│   └── theme/
│       ├── Color.kt                # Dark navy palette
│       ├── Theme.kt                # Material 3 dark theme
│       └── Type.kt                 # Accessible typography
├── assistant/
│   ├── AssistantController.kt      # Core coordinator & state machine driver
│   ├── AssistantState.kt           # Explicit state machine sealed hierarchy
│   ├── AssistantService.kt         # Android 14+ FOREGROUND_SERVICE_MICROPHONE
│   └── CommandParser.kt            # Natural voice command parser
├── speech/
│   ├── SpeechRecognitionEngine.kt  # Modular speech engine interface
│   ├── WhisperSpeechEngine.kt      # whisper.cpp JNI wrapper implementation
│   ├── SpeechRecognizerManager.kt  # Audio pipeline interface
│   ├── WhisperSpeechRecognizerManager.kt # 16kHz PCM AudioRecord & VAD processing
│   ├── WakeWordDetector.kt         # Voice-gated "Hey Nova" keyword spotter
│   ├── SpeechModelManager.kt       # Local model download & lifecycle manager
│   └── SpeechRecognitionListener.kt# Partial/final hypothesis callbacks
├── cpp/
│   ├── CMakeLists.txt              # NDK build targeting arm64-v8a, armeabi-v7a, x86_64
│   ├── jni.c                       # Official JNI bridge to whisper.cpp
│   └── whisper/                    # whisper.cpp & GGML C/C++ engine sources
├── contacts/
│   ├── ContactModel.kt             # Data classes (Contact, PhoneNumber, MatchResult)
│   ├── ContactsRepository.kt       # Queries device Contacts Provider safely
│   └── ContactMatcher.kt           # Multi-tiered deterministic name matcher
├── calling/
│   └── CallManager.kt              # Handles ACTION_DIAL & ACTION_CALL safely
├── audio/
│   └── AudioFeedbackManager.kt     # Offline TTS and ToneGenerator earcons
└── settings/
    └── SettingsRepository.kt       # Jetpack DataStore preferences
```

### State Machine Lifecycle

```
[ IDLE ]
   │
   ├─► [ LISTENING_FOR_WAKE_WORD ] ──(Spotted "Hey Nova")──┐
   │                                                       ▼
   ├─► [ LISTENING_FOR_COMMAND ] ◄──(Manual Mic Tap)───────┘
   │          │
   │      (Parsed "Call [Name]")
   │          ▼
   ├─► [ MATCHING_CONTACT ]
   │          │
   │          ├─► Single Match ──────► [ AWAITING_CONFIRMATION ] ──("Yes")──► [ INITIATING_CALL ] ──► [ CALL_INITIATED ]
   │          ├─► Multiple Matches ──► [ AMBIGUOUS_MATCHES ] ──(Pick)───────┘
   │          └─► No Match / Error ──► [ ERROR ]
   │
   └─► [ CANCELLED ] ◄──("Cancel" / Timeout)
```

---

## 3. Technology Stack & Zero-Cost Compliance

| Component | Selected Technology | License | Zero Cost Assurance |
|---|---|---|---|
| **OS / Platform** | Native Android (API 26 to 35) | Apache 2.0 | Native Android OS APIs |
| **Language** | Kotlin 2.0.21 | Apache 2.0 | Standard Open Source |
| **UI** | Jetpack Compose + Material 3 | Apache 2.0 | AndroidX Open Source |
| **Speech Engine** | Vosk Android (`0.3.47`) | Apache 2.0 | 100% Free, on-device, offline |
| **Acoustic Model** | `vosk-model-small-en-us-0.15` | Apache 2.0 | Free download (~40MB) |
| **Contacts** | Android `ContactsContract` | Built-in | Device local SQLite provider |
| **Telephony** | `Intent.ACTION_DIAL` / `ACTION_CALL` | Built-in | System default dialer |
| **Voice Synthesis** | Android `TextToSpeech` | Built-in | Native device TTS engine |
| **Audio Cues** | Android `ToneGenerator` | Built-in | Native hardware audio tones |
| **Settings** | AndroidX Preferences DataStore | Apache 2.0 | Local on-device protobuf/file |

---

## 4. Android Permissions & Background Service Compliance

### Required Permissions Declared in `AndroidManifest.xml`:

1. `RECORD_AUDIO`: Required for microphone access to process voice commands and spot `"Hey Nova"`.
2. `READ_CONTACTS`: Required to query the device contact list in memory to resolve spoken names to phone numbers. Contacts are **never** copied to external databases and **never** transmitted over the network.
3. `POST_NOTIFICATIONS`: Required on Android 13+ (API 33+) to show the ongoing foreground service notification.
4. `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MICROPHONE`: Required on Android 14+ (API 34+) to maintain microphone access when running as a foreground service.
5. `CALL_PHONE` *(Optional)*: Only requested if the user explicitly turns on Direct Calling in Settings. Default is `ACTION_DIAL`, which does not need this permission.

### Platform Limitations & Background Rules:

- **Android 14+ (API 34) Background Mic Restrictions**: Google has instituted strict policies on continuous microphone access when the app is in the background. While `FOREGROUND_SERVICE_MICROPHONE` is properly declared, background listening may be suspended by the OS if the app has been out of view for an extended period. If this occurs, opening Nova or tapping the notification action ("Listen Now") immediately resumes active listening.
- **Battery Optimization & Doze Mode**: Manufacturers (Xiaomi, Samsung, Huawei, etc.) aggressively kill background services. For reliable wake-word detection with the screen off:
  1. Open Android **Settings** -> **Apps** -> **Nova**.
  2. Select **Battery** -> Change from *Optimized* to **Unrestricted**.
  3. Enable **Autostart** if present on the device.
- **Microphone Conflict**: If another app (e.g. phone call, video camera, voice recorder) claims exclusive microphone focus, Nova gracefully pauses and resumes when audio focus returns.

---

## 5. Offline Speech Model Setup

Nova uses the lightweight, highly efficient Vosk small English model (`vosk-model-small-en-us-0.15`), which requires only ~40MB of storage.

### Option A: Automatic Loading from Internal / External Storage
1. Download the model zip from Alpha Cephei:
   ```bash
   curl -LO https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
   unzip vosk-model-small-en-us-0.15.zip
   ```
2. Push the unzipped folder to the device application storage:
   ```bash
   adb push vosk-model-small-en-us-0.15 /sdcard/Android/data/com.nova.callassist/files/model
   ```
3. Restart Nova. The Settings screen will show: `Model: vosk-model-small-en-us-0.15 (Ready)`.

### Option B: Bundled in Assets
Place the unzipped model files into:
`app/src/main/assets/model/`
Nova will automatically unpack and verify the model on first launch.

### Option C: Built-in Typed Command Testing
Nova includes an in-app **Typed Command Fallback** bar on the main screen. You can type commands like `"Call Arun"`, `"Call Rahul Kumar"`, `"Yes"`, or `"Cancel"` directly to verify contact matching, candidate disambiguation, and phone call dispatching without needing an acoustic model.

---

## 6. How to Build & Test

### Prerequisites
- JDK 17
- Android SDK Platform 35 (or 36)
- Android Build Tools 35.0.0

### Run Unit Tests
```bash
./gradlew test
```
All unit tests for Contact Matching, Command Parsing, and State Machine transitions will run and report 100% success.
Reports are generated at: `app/build/reports/tests/testDebugUnitTest/index.html`.

### Build Debug APK
```bash
./gradlew assembleDebug
```
The compiled, packaged debug APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Install on a Real Device or Emulator
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 7. Real Device Testing Checklist

| Test Scenario | Steps to Execute | Expected Result |
|---|---|---|
| **Exact Contact Call** | Say: *"Call Rahul"* (where Rahul is a contact) | Nova responds: *"Do you want me to call Rahul?"* Shows confirmation card. Say *"Yes"* or tap **Call Now** -> dialer opens. |
| **Ambiguous Contact Disambiguation** | Say: *"Call Arun"* (contacts: *Arun Kumar*, *Arun Prakash*) | Nova announces: *"I found 2 contacts matching Arun. Which one would you like to call?"* Displays numbered list of candidates. |
| **Verbal Option Selection** | When ambiguous list is shown, say: *"Option 1"* or *"Arun Kumar"* | Selected contact is chosen and Nova requests confirmation. |
| **Contact Without Number** | Ask to call a contact with no phone number | Nova announces: *"[Name] has no phone number."* and returns to standby. |
| **Multiple Phone Numbers** | Ask to call a contact with Home and Mobile numbers | Nova selects the primary mobile number by default. |
| **Spoken Cancellation** | At confirmation prompt, say: *"Cancel"* or *"Don't call"* | Nova plays cancel chime, speaks *"Cancelled."*, and returns to standby. |
| **Microphone Denial** | Deny `RECORD_AUDIO` permission | Nova displays yellow **Permissions Required** banner with one-click **Grant Permissions** button; does not crash. |
| **Contacts Denial** | Deny `READ_CONTACTS` permission | Nova handles missing permission gracefully and requests user consent. |
| **Manual Mic Fallback** | Tap the central pulsing microphone icon | Mic turns green, Nova prompts *"Yes, I'm listening."*, and active command listening begins. |
| **Direct Calling Mode** | Toggle **Direct Calling** in Settings, grant `CALL_PHONE` | Direct call placed via `ACTION_CALL` upon confirmation. |
