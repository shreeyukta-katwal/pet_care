#  PetCare — Android Pet Routine & Checklist Tracker

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org/)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26%2B-orange.svg)](https://developer.android.com/about/dashboards)
[![Architecture](https://img.shields.io/badge/Architecture-MVVM-brightgreen.svg)]()
[![Database](https://img.shields.io/badge/Database-Room-yellowgreen.svg)]()
[![Design](https://img.shields.io/badge/Design-Material%203-teal.svg)]()

**PetCare** is a modern, native Android application designed to help pet owners stay on top of their pets' daily health, grooming, and routine schedules. PetCare combines clean MVVM architecture with offline-first Room persistence, scheduled reminders, SMS plan delegation, and intuitive gesture and sensor controls.

---

## Key Features

### User Authentication & Scoped Storage
- Local user signup and login with secure salted **SHA-256** password hashing.
- Strict data isolation ensuring each user only accesses their own pets and routines.
- Persistent session management with `SharedPreferences`.

### Pet Profile Management
- Comprehensive pet profiles: Name, species, breed, age, weight, diet, allergies, vaccinations, favorite toys, and notes.
- Custom avatar support saved to internal app storage.
- Full CRUD operations (Add, Edit, View, and Delete with cascade cleanup).

### Smart Routines & Daily Checklists
- Organize care tasks into **5 distinct categories**:
  - Feeding
  - Exercise
  - Grooming
  - Medication
  - Healthcare & Vet Appointments
- Flexible scheduling: Daily or custom recurring days of the week (Mon–Sun bitmask).
- Automatic daily checklist generation with real-time progress indicators.
- Automatic midnight date rollover (no heavy background jobs needed).

### SMS Care Delegation
- Delegate a pet's daily care plan to a pet sitter, friend, or family member.
- Automatically compiles a clean, structured SMS summary containing tasks, timings, supplies, diet, and special notes.

### Gesture Controls & Sensor Integration
- **Swipe Right**: Quickly mark a task as completed.
- **Swipe Left**: Delete an item with an instant **Undo** Snackbar.
- **Shake-to-Reset**: Accelerometer sensor integration allowing users to shake their device to reset today's checklist.
- **Double-Tap**: Tap on any pet photo to view an enlarged preview dialog.
- **Long-Press**: Convenient multi-selection for routine delegation.

### Notifications & Reminders
- Built with **Android WorkManager** to trigger exact time-based care notifications so you never miss a feeding or medication window.

---

## Architecture & Tech Stack

The application strictly follows Google's recommended **Modern Android Architecture (MVVM)** guidelines:

- **Language:** [Kotlin](https://kotlinlang.org/)
- **UI Framework:** Single Activity + Android Jetpack Navigation Component (Fragments), ViewBinding, and Material Design 3.
- **Asynchronous Work:** Kotlin Coroutines & `Flow` / `LiveData`.
- **Local Persistence:** [Room](https://developer.android.com/training/data-storage/room) with KSP and TypeConverters.
- **Background Scheduling:** [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) for battery-efficient notifications.
- **Dependency Management:** Clean Manual Dependency Injection via application-level `AppContainer`.
- **Hardware Sensors:** Android `SensorManager` & accelerometer for shake gesture detection.

### Architecture Flow
```
UI (Fragment) ──► ViewModel (LiveData / StateFlow) ──► Repository ──► Room DAO (SQLite)
      │                                                     │
ViewBinding / Gestures                                WorkManager / Sensors
```

---

## Getting Started

### Prerequisites
- Android Studio Ladybug / Jellyfish or newer
- JDK 17+
- Android SDK with **Min SDK 26** and **Target SDK 34/35**
- Android device or emulator running API 26+

### Installation & Run

1. **Clone the repository:**
   ```bash
   git clone https://github.com/<your-username>/PetCare.git
   ```
2. **Open in Android Studio:**
   - Launch Android Studio.
   - Select **Open** and choose the `Pet Care` directory.
3. **Build the project:**
   ```bash
   ./gradlew assembleDebug
   ```
4. **Run the App:**
   - Select an emulator or connected physical Android device.
   - Click **Run (Shift + F10)**.

---

## Testing

Run local unit tests:
```bash
./gradlew testDebugUnitTest
```

Run connected Android instrumentation tests (Room DB & migrations):
```bash
./gradlew connectedAndroidTest
```

---

## Project Structure

```text
com.petcare.app/
├── data/
│   ├── db/          # Room Database, Entities (User, Pet, Task), DAOs, TypeConverters
│   ├── repository/  # UserRepository, PetRepository, TaskRepository
│   ├── security/    # PasswordHasher (Salted SHA-256)
│   └── session/     # SessionManager implementation
├── gesture/         # Swipe callbacks, DoubleTap listener, ShakeDetector
├── reminder/        # WorkManager worker & ReminderScheduler
└── ui/
    ├── auth/        # Login & Signup screens
    ├── checklist/   # Daily checklist with progress tracker
    ├── delegate/    # SMS delegation builder & screen
    ├── pet/         # Pet listing & management forms
    ├── task/        # Task creation & edit forms
    └── welcome/     # Onboarding & landing screen
```

---

## License

This project is created for educational and portfolio purposes.
