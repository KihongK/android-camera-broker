# Android Camera Multiplexing & WebRTC Project

## 1. Project Overview
This project aims to build an Android system where a single hardware camera resource is shared among multiple client applications simultaneously.
* **Role:** Developing the **WebRTC Client App** that consumes shared camera frames.
* **Target Device:** Custom Android Device (Robot/Kiosk) running Android OS.
* **Core Problem:** Android Camera2 API is exclusive. Need a "Broker" architecture to multiplex the camera stream.

## 2. System Architecture
### Components
1.  **CameraBrokerService (Server):**
    * Exclusively connects to `CameraManager`.
    * Allocates `SharedMemory` (via `ashmem` or `AHardwareBuffer`).
    * Writes frame data (YUV/Raw) and metadata (timestamp, dims) to shared memory.
    * Exposes `AIDL` interface to broadcast `FileDescriptor` (FD) and sync signals.
2.  **Client Applications (Subscribers):**
    * **WebRTC App (My Scope):** Reads frames from SharedMemory and streams via P2P.
    * **Detection App (Other Team):** Reads frames for CV/ML processing.

### Data Flow
`Camera H/W` -> `Broker Service` -> `SharedMemory` -> `IPC (Binder/AIDL)` -> `Client Apps`

## 3. Tech Stack & Requirements
* **Language:** Kotlin (Preferred) / Java.
* **Min SDK:** API 27 (Android 8.1 Oreo) - Essential for `android.os.SharedMemory`.
* **IPC:** AIDL (Android Interface Definition Language).
* **Memory Strategy:** * **Goal:** Zero-copy using `AHardwareBuffer` (pass via Parcelable FD).
    * **Current PoC:** `android.os.SharedMemory` (ByteBuffer copy).
* **WebRTC:** `libwebrtc` (Google WebRTC Android SDK).

## 4. Implementation Guidelines (WebRTC App)
### Custom VideoCapturer
* Do **NOT** use `Camera2Enumerator` or `Camera2Capturer`.
* Implement `VideoCapturer` interface manually.
* Create a class (e.g., `SharedMemoryVideoCapturer`) that:
    1.  Receives signal from Broker via AIDL callback.
    2.  Reads/Maps data from `SharedMemory`.
    3.  Wraps data into `VideoFrame` (NV21Buffer or TextureBuffer).
    4.  Calls `CapturerObserver.onFrameCaptured()`.

### Synchronization
* Always verify frame integrity using **Timestamp** (`System.nanoTime`).
* Handle Ring Buffer logic if the Broker implements multiple frame buffers.

## 5. Current Task: PoC (Proof of Concept)
We are currently building a prototype on a **standard Android phone** (non-rooted) to verify the logic.

### PoC Constraints
* **App A (Broker):** Runs as a **Foreground Service** to keep Camera access active.
* **App B (Client):** Binds to App A and renders the shared frame on screen (ImageView) or streams via WebRTC.
* **Resolution:** VGA (640x480) for testing to minimize memory copy overhead.

## 6. Coding Conventions
* **Permissions:** Ensure `CAMERA`, `FOREGROUND_SERVICE`, and permissions defined in AIDL are handled.
* **Manifest:** Broker Service must set `android:exported="true"`.
* **Error Handling:** Gracefully handle IPC disconnections (`DeadObjectException`).