# WearBluetoothManager

A small Wear OS utility that discovers Bluetooth Classic and Bluetooth Low Energy (BLE) devices and requests system pairing.

## Target device
- Samsung Galaxy Watch 7 / Wear OS
- Package: `com.wearbluetoothmanager`
- Min SDK: 30
- Compile SDK: 36
- Target SDK: 35
- Version: 1.0.2 (versionCode 3)

## Features in v1.0.2
- Classic Bluetooth discovery (`BluetoothAdapter.startDiscovery()`)
- BLE discovery (`BluetoothLeScanner.startScan()`)
- Combined device list, de-duplicated by Bluetooth address
- Shows device name, discovery source and bond state
- Pair button calls Android's `BluetoothDevice.createBond()`; Android/Wear OS handles the system pairing confirmation or PIN UI
- No root, Shizuku, or privileged permissions

## OMOTON KB568 pairing
1. Unfold the keyboard and switch it on.
2. Hold `Fn` + `BT2` (the KB568 A0 manual labels this `FN+BTVBT2`) for about 3 seconds, until the blue LED flashes rapidly.
3. Open WearBluetoothManager on the watch and tap **Eszközök keresése**.
4. Select the keyboard when it appears (often named `Bluetooth Keyboard` or similar) and tap **Párosítás**.
5. If Wear OS displays a pairing code, follow the on-screen instructions. If it asks for a code to be typed on the keyboard, type it there and press Enter.

If your particular KB568 revision has BT1/BT2/BT3 channel keys, choose an unused channel and hold its BT key with Fn.

## Build with GitHub Actions
1. Create a new GitHub repository.
2. Upload/push the contents of this folder to the repository root (the `.github` folder must be included).
3. Open **Actions** and enable workflows if GitHub asks.
4. Run **Android CI** using **Run workflow**, or push a commit to `main`/`master`.
5. Open the completed workflow run and download the `WearBluetoothManager-debug` artifact.
6. Extract `app-debug.apk` and install it on the Galaxy Watch 7 (for example with ADB over Wi-Fi).

The workflow installs JDK 17, Android SDK 36 and Gradle 8.13, then builds `:app:assembleDebug`.

## Important limitations
- Discovery can only show devices that are actively advertising or discoverable. Put the keyboard into pairing mode before scanning.
- A successful bond request does not guarantee that the watch firmware exposes or supports the keyboard's HID profile. The app cannot add missing system HID support or force a profile connection.
- Wear OS may show its own system pairing dialog. This is expected and required for secure pairing.
- BLE scan results can be restricted by Android's Bluetooth/location permission policy and by the device firmware.

## Permissions
The app requests `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` and `ACCESS_FINE_LOCATION`. Fine location is requested because Android's general discovery APIs may require it; the app does not use location data.


## Important: repository root layout
After extracting this ZIP, upload the *contents* of this folder to the repository root.
The workflow file must be exactly:
`.github/workflows/android.yml`

Do not put the project inside an extra `WearBluetoothManager/` folder in the repository.
If GitHub's web upload interface hides `.github`, create the workflow file directly on GitHub
using **Add file → Create new file**, enter `.github/workflows/android.yml`, and paste
the contents of the included workflow file. Alternatively, use Git from a terminal so hidden
directories are included.

Versioning policy:
- Every new project release increments `versionCode`.
- Every release updates `versionName`.
- v1.0.2 uses versionCode 3 and applicationId `com.wearbluetoothmanager`.


## v1.0.2 build fix
The GitHub Actions Android SDK setup now installs only valid packages:
`platform-tools`, `platforms;android-36`, and `build-tools;36.0.0`.
It no longer requests the obsolete SDK package named `tools`, which caused
`Warning: Failed to find package 'tools'` and stopped the previous build.
