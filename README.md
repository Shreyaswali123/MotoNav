# MotoNav

**MotoNav** is a dedicated motorcycle navigation system that transfers a planned route from an Android smartphone to an ESP32-S3 embedded device, then uses the device's own GNSS for local position tracking and navigation.

The system is designed around a simple workflow:

```mermaid
flowchart LR
    A[Android Phone] -->|BLE Route Transfer| B[ESP32-S3 MotoNav]
    B --> C[Route Storage]
    B --> D[GNSS Position]
    C --> E[Route Matching]
    D --> E
    E --> F[Navigation Instructions]
    F --> G[Navigation Display]
```

## System Architecture

```mermaid
flowchart TB
    subgraph PHONE["Android Smartphone"]
        A1[Route Selection]
        A2[Route Data Preparation]
        A3[BLE Communication]
        A1 --> A2 --> A3
    end

    subgraph DEVICE["MotoNav Embedded Device"]
        B1[ESP32-S3]
        B2[BLE GATT Server]
        B3[Route Storage]
        B4[Navigation Engine]
        B5[Display Interface]

        B1 --> B2
        B1 --> B3
        B1 --> B4
        B4 --> B5
    end

    C[GNSS Receiver] --> B4
    A3 <-->|Bluetooth Low Energy| B2
    B3 --> B4
```

## Software Architecture

```mermaid
flowchart TB
    subgraph APP["Android Application"]
        A1[Destination / Route Selection]
        A2[Route Encoding]
        A3[BLE Client]
        A1 --> A2 --> A3
    end

    subgraph FW["ESP32-S3 Firmware"]
        F1[BLE GATT Server]
        F2[Packet Parser]
        F3[CRC Validation]
        F4[Route Manager]
        F5[GNSS Interface]
        F6[Position & Route Matching]
        F7[Navigation State]
        F8[Display Driver]

        F1 --> F2 --> F3 --> F4
        F4 --> F6
        F5 --> F6
        F6 --> F7 --> F8
    end

    A3 <-->|START_ROUTE / ROUTE_DATA / END_ROUTE| F1
```

## Route Transfer Flow

```mermaid
sequenceDiagram
    participant P as Android App
    participant E as ESP32-S3
    participant S as Route Storage

    P->>E: BLE Connect
    P->>E: START_ROUTE
    P->>E: ROUTE_DATA packets
    E->>E: Parse packets
    E->>E: CRC / integrity check
    P->>E: END_ROUTE
    E->>S: Store verified route
    E-->>P: ROUTE READY
    P->>E: BLE Disconnect
```

## Navigation Data Flow

```mermaid
flowchart LR
    A[Stored Route] --> C[Route Matcher]
    B[GNSS Position] --> C
    C --> D[Current Route Segment]
    D --> E[Distance / Direction Analysis]
    E --> F[Next Turn]
    F --> G[Display Output]
```

## Hardware Architecture

```mermaid
flowchart TB
    P[Motorcycle Power / USB] --> PM[Power Regulation]

    PM --> MCU[ESP32-S3]
    MCU <-->|BLE| PHONE[Android Smartphone]
    GNSS[GNSS Receiver] --> MCU
    MCU --> DISP[Navigation Display]

    MCU --> STORAGE[Route Storage / Non-Volatile Memory]
```

## Key Communication Protocol

MotoNav uses a packet-based BLE route transfer protocol.

```text
START_ROUTE
    ↓
ROUTE_DATA
    ├── Packet 1
    ├── Packet 2
    ├── Packet 3
    └── ...
    ↓
END_ROUTE
    ↓
CRC Verification
    ↓
ROUTE READY
```

After the route is successfully stored, the smartphone can disconnect. The embedded device then continues navigation using its onboard GNSS and the stored route.

## Repository Structure

```text
MotoNav/
├── app/                  # Android application
├── firmware/             # ESP32-S3 firmware
├── README.md
├── build.gradle.kts
├── settings.gradle.kts
└── ...
```

## Technology Stack

```text
Mobile        : Android
MCU           : ESP32-S3
Connectivity  : Bluetooth Low Energy (BLE)
Firmware      : ESP-IDF
Positioning   : GNSS
Display       : Dedicated navigation display
Protocol      : Custom BLE GATT packet protocol
```

## Development Status

MotoNav is an embedded navigation prototype under active development.

The current implementation includes the Android-side route transfer concept, ESP32-S3 BLE communication, packet-based route transfer, route integrity verification, and local route storage. GNSS-based navigation, route matching, and final display integration are part of the ongoing development.

---

**MotoNav — Smartphone-assisted route transfer, embedded navigation.**
