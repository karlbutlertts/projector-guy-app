# Hardware-specific optimisation profiles

Candidate feature; customer APK remains 2.11.3 until device validation is complete.

The native optimiser now selects a fixed local profile using the device, hardware,
Android API and active stock launcher. A marketing-model selection cannot override
this check. The registry contains XBJ 360 (uiw4068pxm / sun50iw15p1 / API 34) and
XBJ B11 (Hi3751V350 / bigfish / API 28). A5 Pro has no automatic profile.

Optimise sets the three Android animation scales to zero and disables optional
Android printing and Traceur packages if installed. B11 additionally disables the
Chromium test shell. The stock launcher, stores, casting, System WebView and Netflix
navigation helper remain unchanged. No picture calibration or Netflix navigation
fix is included; speed and brightness gains are unmeasured.

The engine saves original values before changes, isolates saved state by device and
firmware, verifies writes, skips absent packages, preserves the first snapshot on
repeat runs and attempts rollback on failure. Restore returns the recorded values,
including unset settings and exact package enabled states. A previous 360 optimiser
snapshot can be restored separately.

Validation on 7 November 2026: 15 automated tests pass, including both shipped
profile configurations, repeat apply, exact restore, missing packages and rollback.
Debug and release APK builds plus Android lint are checked in the isolated candidate.
These checks simulate shell responses; they do not establish device-side ADB
permission, functional UI operation or projector performance.

Before a customer APK release, connect the target unit, capture its current state,
exercise Optimise, repeat Optimise and Restore, then verify settings and the stock
launcher, stores, casting and Netflix helper. The earlier 360 computer-side bench
round trip is dated evidence; the B11 shared engine still needs a device round trip.
The app's existing web entry is currently shown for the 360 selection; normal B11
entry routing also needs to be validated before customer distribution.

Build with Java 17 and Android SDK 34:

```
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug
```

Only the engine, native integration, registry, tests and these notes belong to this
candidate. Native Projector tools/MENU additions, the alternate profile-test screen,
A5 maintenance scripts and device dumps are outside this change.
