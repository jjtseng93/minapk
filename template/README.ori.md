<div align="center">

# Tetris

**Compact, kilobyte-scale Android Tetris built in pure Java.**

Pure Java · SurfaceView & Canvas · No Gradle · No AndroidX · 16.8 KB

[**English**](README.md) · [Русский](README.ru.md) · [Root README](../README.md)

<br>

<img src="../.github/assets/badges/java.svg" alt="Java 8">  <img src="../.github/assets/badges/android.svg" alt="Android">  <img src="../.github/assets/badges/no-gradle.svg" alt="No Gradle">  <img src="../.github/assets/badges/apk-size.svg" alt="APK Size">  <img src="../.github/assets/badges/license.svg" alt="MIT License">

<br><br>

<img src="../docs/images/tetris-shot-v2.jpg" alt="Tetris screenshot" width="320">

</div>

---

> [!NOTE]
> **No Gradle project inside.** Compiled manually with `aapt2 -> ecj -> R8 -> zipalign -> apksigner`.

**Tetris** is a minimal, fast, single-file Android game written from scratch using raw Android SDK APIs (`SurfaceView` and `Canvas`).

---

## <img src="../.github/assets/icons/bolt.svg" width="20"> What's New (Update 04.08.2026)

- <img src="../.github/assets/icons/bolt.svg" width="14"> **Ultra-Compact Single-File Architecture (`TetrisUltra.java`)**: Rebuilt core game logic, rendering engine, and state machine into a single optimized Java file to reduce APK footprint.
- <img src="../.github/assets/icons/gamepad.svg" width="14"> **Smooth Touch Gestures**: Smooth horizontal/vertical dragging (`drag/swipe`), fast downward swipe for hard drops, and tap-to-rotate.
- <img src="../.github/assets/icons/terminal.svg" width="14"> **Interactive Pause & Resume**: Added on-screen `[PAUSE]` / `[RESUME]` toggle button and full Activity `onPause()` / `onResume()` lifecycle integration.
- <img src="../.github/assets/icons/flask.svg" width="14"> **Zero-Allocation Audio Engine**: Replaced runtime `AudioTrack` allocations with static memory-mapped sound buffers (zero heap allocations during gameplay).
- <img src="../.github/assets/icons/layers.svg" width="14"> **Universal Aspect Ratio Centering**: Dynamic grid and sidebar positioning that automatically centers on wide landscape, narrow portrait, and tablet screens.

---

## <img src="../.github/assets/icons/package.svg" width="20"> Build Output

| Artifact | Size | Description |
| :--- | :--- | :--- |
| `build/LowBlocks-release.apk` | **16,848 B** | Signed, R8-shrunk release build |
| `build/LowBlocks.apk` | **16,848 B** | Ready-to-install release build |

---

## <img src="../.github/assets/icons/gamepad.svg" width="20"> Features

- All 7 classic tetrominoes (`I`, `J`, `L`, `O`, `S`, `T`, `Z`).
- Ghost piece landing preview.
- Wall-kick rotation logic.
- Score, line counter, level progression, and high score saving (`SharedPreferences`).
- Speed scaling as level increases.
- Next-piece preview box.
- Particle explosion effects on line clears.

---

## <img src="../.github/assets/icons/terminal.svg" width="20"> Controls

| Gesture / Touch | Action |
| :--- | :--- |
| **Tap** | Rotate piece |
| **Drag Left / Right** | Move piece horizontally |
| **Drag Down** | Soft drop |
| **Fast Swipe Down** | Hard drop |
| **Tap Pause Button** | Pause / Resume game |
| **Tap (Game Over screen)** | Restart game |

---

## <img src="../.github/assets/icons/book.svg" width="20"> Build Instructions

To build the project on Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

- [Manual build guide](../manual-build/README.md)
- [R8 / ProGuard shrinking guide](../PROGUARD_README.md)

---

<div align="center">

<sub>MIT License · See <a href="../LICENSE">LICENSE</a> for details</sub>

</div>
