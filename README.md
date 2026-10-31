# Apophis Simulator Android 0.4.0 (experimental)

Native Kotlin Android app with OpenGL ES 2.0 rendering and approximate solar-system dynamics.

## Features
- OpenGL ES 2.0 shaded 3D sphere geometry for the Sun, eight planets and Apophis (apparent sizes enlarged for visibility).
- Smooth frame animation via interpolation between daily integration snapshots, with playback speed controls.
- Pinch zoom, touch pan, heliocentric/geocentric camera, 35,000 km camera-range preset.
- JPL Horizons **geometric Earth–Apophis separation at 00:00 UTC** retrieved independently using heliocentric vectors; daily offline cache.
- JPL SBDB osculating elements with offline cache, integrated in a simplified solar-system model.

## Critical scientific limitations
The drawn planetary positions use approximate circular coplanar orbits; their phases are not high-precision ephemerides. The asteroid is propagated with velocity Verlet and simplified gravitational perturbations. **The rendered Earth–Apophis separation is not a reliable astronomical prediction.** The separate Horizons reference (when available) is an actual reference distance for 00:00 UTC on the selected day, not for the continuously interpolated animation time. The app is not an impact predictor. Apophis has no predicted Earth impact in 2029.

## Build
The GitHub Actions workflow builds a debug APK. Debug builds on separate runners can have different signing keys and cannot reliably update each other. A stable release-signing pipeline remains to be implemented.
