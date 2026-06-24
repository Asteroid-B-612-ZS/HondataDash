# Changelog

All notable changes to HondataDash, from the first in-car usable build to the current release.

The app is a lightweight Android dashboard for displaying Hondata FlashPro Bluetooth data on a dedicated 800×480 landscape head unit. It is not a general OBD tool and does not replace Hondata FlashPro Manager.

---

## V2.7.0 — Main Value Semantic Colors
*2026-06-24 · versionCode 24*

**Summary**
Built on the verified V2.6.9 Bluetooth lifecycle and session-integrity baseline, this release adds semantic main-value colors for L.TRIM, MAP, IGN, and S.TRIM so safe, warning, and abnormal ranges are easier to read at a glance.

**Changed**
- L.TRIM / S.TRIM: absolute trim within ±5% is green, within ±15% is yellow, outside ±15% is red.
- IGN: 0° or higher is green, -5° to 0° is yellow, below -5° is red.
- MAP: evaluated using the displayed relative boost value in bar; up to 1.45bar is green, 1.45~1.60bar is yellow, above 1.60bar is red.
- ECT: normal operating range (80~95°C) changed from white to green; cold blue and overheat red are unchanged.
- Scale-bar zones remain unchanged (V2.6.9 direction/emotion mode preserved).

**Unchanged**
- DFCO / SYNC semantic display behavior is unchanged.
- A/F Lambda semantic coloring and WOT lean flashing are unchanged.
- A/F / IGN / S.TRIM low-confidence gray behavior is unchanged and still has final visual priority.
- Bluetooth connection, reconnect, protocol parsing, state tracking, and MAX/MIN history are unchanged.

---


## V2.6.7 — Engine Baseline, Bluetooth Reconnect Hardening, Confidence Display Cleanup
*2026-06-15 · versionCode 21*

**Summary**
Defensive pass after V2.6.6: remove the startup self-test, give MAX/MIN a known-good baseline at ignition-on, keep the value layer alive across short Bluetooth dropouts, and stop the reconnect path from silently dying.

**Changed**
- Removed the startup self-test sweep from the production flow; `onCreate` now goes straight to connecting.
- Added a one-shot engine-running baseline override: at engine start, each card takes its first valid sample as the MAX/MIN baseline (`engineBaselineApplied[8]`), gated per card — ethanol waits for the settling gate. The flag clears when the engine stops so the next start re-baselines.
- Short Bluetooth dropouts (under 5 minutes) now preserve the value and extreme layer; only a long dropout with RPM ≤ 300 resets the session.
- Confidence gray refined: threshold 0.82 → 0.78, fixed `0xFF888888` color at full alpha (no alpha-floor gradient). Warmup and dynamic low-reference detection no longer key off `closedLoop`.

**Fixed**
- A failed handshake no longer sets `intentionalDisconnect`, which previously blocked automatic reconnect.
- `RuntimeException` in the poll loop is now caught so the thread no longer dies silently without triggering a reconnect.
- RFCOMM cleanup wait raised 1s → 2.5s for older Android Bluetooth stacks that release the channel slowly.
- Resource release consolidated into `closeAllBluetoothResources` / `cleanupFailedConnection` / `scheduleReconnect` / `closeQuietly`.

**Why it matters**
- Extremes start from a realistic reading instead of the first noisy byte after ignition-on.
- A brief Bluetooth dropout no longer throws away a session worth of MAX/MIN.
- Reconnect actually happens again after handshake or parse failures.

**Notes**
- Status: pending in-car verification.

---

## V2.6.6 — Self-Test Isolation, Confidence Gray, Ethanol Settling
*2026-06-11 · versionCode 20*

**Summary**
Three quality fixes for extreme values and low-confidence display, built on top of the V2.6.5 verified baseline.

**Changed**
- Startup self-test values are now isolated from normal extreme records via `resetAllExtremeHistory()` after the sweep.
- Engine-dependent extremes (L.TRIM, MAP, A/F, IGN, S.TRIM) reset per engine run cycle via `engineExtremeSessionActive`.
- A/F, IGN, S.TRIM show gray text when confidence drops below 0.82, driven by `EngineSemanticState.textAlpha()` with a 0.70 alpha floor.
- Ethanol extremes require a 3s minimum observation plus 1.5s stability (delta < 0.3%) before recording, fixing a MIN=0 caused by CANFlex 0→real ramp-in.

**Why it matters**
- Self-test no longer pollutes session extremes.
- Ethanol MIN stops showing bogus zeros after a fresh connect.

**Notes**
- This was the self-test *isolation* attempt. V2.6.7 later removed self-test entirely, so this version describes the intermediate state, not the final design.

---

## V2.6.5 — Unified Main Value Height and Fixed-Width Signs
*2026-06-08 · versionCode 19*

**Summary**
Display consistency pass: all cards use the same main value height, and +/- signs render at a fixed equal width.

**Changed**
- `MAIN_VALUE_SP = 112f` unified across all eight cards, removing the 112/104/102/100 spread.
- `FixedSignSpan` refactor: `targetSignWidthPx` replaces `signScaleX`, using the `-` glyph as the reference width and compressing `+` to match.
- `getScaleForChar()` computes scaleX per character from its raw width.
- `SIGN_TARGET_WIDTH_SCALE = 0.82f` replaces the old `SIGN_SCALE_X`.

**Why it matters**
- Removes visible height inconsistency between cards and the asymmetric +/- width on the compact layout.

---

## V2.6.4 — Fixed Sign, Startup Polish, Engine Gate
*2026-06-07 · versionCode 18*

**Summary**
Fixed-width sign rendering, a startup self-test display pass, and the first engine-running gate for extreme recording.

**Changed**
- Introduced fixed-width sign rendering so signed values stay aligned.
- Added startup self-test display polish.
- Added the first engine-running gate before recording extremes.

**Notes**
- The self-test work here was later removed in V2.6.7; do not read this as a long-retained feature.

---

## V2.6.3 — Semantic Alpha and Compact Sign Pass
*2026-06-06 · versionCode 17*

**Summary**
Refined semantic brightness and compact sign rendering on top of the V2.6.2 measurement fix.

**Changed**
- Corrected semantic alpha behavior for DFCO/SYNC display.
- Smoothed main value width adaptation.
- Improved compact sign rendering for signed values.

---

## V2.6.2 — TextScaleX Measurement Fix (Golden Master)
*2026-06-04 · versionCode 16*

**Summary**
Fixed a width measurement instability caused by measuring text after `TextScaleX` had already been applied. This is the first fully display-stable version and the maintenance baseline.

**Changed**
- Switched to unscaled text measurement (`measureTextUnscaled()`) so width fitting no longer oscillates frame to frame.
- Single `TextView` + `FrameLayout` overlay architecture locked in.

**Why it matters**
- Frame-to-frame display fit jitter is gone; this is the version everything since builds on.

**Notes**
- Designated Golden Master — the maintenance baseline for later versions.

---

## V2.6.1 — Display Architecture Verification
*2026-06-04 · versionCode 15*

**Summary**
Verified and corrected the V2.6 display architecture reset across all main cards.

**Changed**
- Focused on consistent behavior across all main cards under the new single-value rendering path.

---

## V2.6 — Display Architecture Reset
*2026-06-04 · versionCode 14*

**Summary**
Reset the main value display architecture around a more stable single-value rendering model.

**Changed**
- Targeted predictable layout, consistent card behavior, and lower complexity for future fixes.

---

## V2.5 — Display Stability Pass
*2026-06-04 · versionCode 13*

**Summary**
Display stability pass on top of the V2.4.3 adaptive architecture.

**Changed**
- Improved semantic overlay behavior, extreme display stability, and scale bar consistency.
- Focused on reducing visual jumping during real driving.

---

## V2.4.3 — Single TextView Adaptive Rendering
*2026-06-04 · versionCode 12*

**Summary**
Moved the main value onto a simpler single-`TextView` adaptive rendering path.

**Changed**
- `formatMainText()` returns the complete formatted string; a single `measureText` call does precise fitting.
- Reduced layout fragmentation and made width fitting more predictable.

---

## V2.4.2 — Main Value Font Maximization
*2026-06-04 · versionCode 11*

**Summary**
Relaxed overly conservative fitting margins so the main value could be larger without clipping.

**Changed**
- Corrected conservative fit margins; enlarged the main value within the no-clip constraint.

---

## V2.4.1 — Width-Only Main Value Fit
*2026-06-03 · versionCode 10*

**Summary**
Changed the main value fitting strategy to preserve height while adapting width.

**Changed**
- Main value fit primarily adjusts width, no longer sacrificing height.
- Reduced vertical inconsistency between cards.

---

## V2.4 — Display Fit Completion
*2026-06-03 · versionCode 9*

**Summary**
Closed out the main display fit on the compact 800×480 layout.

**Changed**
- Handled wide semantic labels and signed values without clipping.

---

## V2.3 — Display Truth Pass
*2026-06-03 · versionCode 8*

**Summary**
Shifted focus from "looks good" to "is accurate": stronger combustion-invalid gating and semantic SYNC handling.

**Changed**
- Added stronger combustion-invalid gating and semantic SYNC handling.
- Protected A/F, IGN, and S.TRIM from DFCO/SYNC contamination.

**Why it matters**
- Values shown during invalid combustion states no longer get mistaken for stable tuning data.

---

## V2.2 — Session Extremes and Semantic Alarm Filtering
*2026-06-03 · versionCode 7*

**Summary**
Separated session-level extremes from recent transient extremes and improved A/F alarm behavior.

**Changed**
- Split session extremes from recent dynamic extremes.
- A/F alarm no longer misfires on coasting, lifting, or shifting.
- Added data freshness awareness.

---

## V2.1 — History Admission and Real-Log Demo
*2026-05-27 · versionCode 6*

**Summary**
Replaced raw MAX/MIN recording with history admission rules, and added a real-log demo playback.

**Changed**
- History admission rules gate which samples are allowed into the extreme records.
- DFCO exit, warmup, and transient spikes no longer pollute tuning-relevant extremes.
- Added real-log demo playback for testing the display logic.

**Why it matters**
- MAX/MIN now reflect stable tuning-relevant readings instead of transient spikes.

---

## V2.0 — ECU Semantic State Engine
*2026-05-24 · versionCode 5*

**Summary**
Reworked engine state detection around ECU strategy signals instead of simple throttle position, and added a confidence dimension.

**Changed**
- `EngineSemanticState` carries four orthogonal dimensions: MainState, SubState/Modifier, and Confidence.
- MainState (mutually exclusive): DFCO / WOT / WARMUP / IDLE / NORMAL.
- WOT redefined for torque-model ECU (L15B7): Open Loop + rich command + boost established + RPM threshold — independent of throttle position.
- Confidence (0.0–1.0) drives text opacity and filter aggressiveness.

**Why it matters**
- WOT is detected correctly regardless of throttle position, which matters for a torque-model ECU where throttle ≠ load.

---

## V1.4 — Response Tuning and Bluetooth Full Reset
*2026-05-24 · versionCode 4*

**Summary**
Reduced perceived state transition delay and added a more complete Bluetooth reset path after disconnects.

**Changed**
- Shortened perceived DFCO/WOT state transition delay.
- Improved DFCO-exit display recovery.
- Introduced the full-reset reconnect path: stop the old thread, close the socket, wait for the Bluetooth stack, then reconnect from scratch.

**Notes**
- This did not "fully solve" Bluetooth drops; V2.6.7 continues to harden reconnect. Read as the first complete reset attempt.

---

## V1.3 — Dynamics Scale Bar and UI Polish
*2026-05-22 · versionCode 3*

**Summary**
Introduced the dynamic `ScaleBarView` and state-aware visual response, plus card layout polish.

**Changed**
- Dynamic scale bar with state-aware visual response.
- Different visual feedback for slow thermal values, mechanical values, and transient values.
- Refined card layout and readability.

---

## V1.2 — UI Correction and Repository Cleanup
*2026-05-21 · versionCode 2*

**Summary**
Corrected display sizing and cleaned obsolete project files.

**Changed**
- Fixed display sizing issues.
- Removed obsolete files and build artifacts so the project structure is clearer.

---

## V1.1 — Data Pipeline Refinement
*2026-05-21 · versionCode 1*

**Summary**
Improved data processing and update behavior for stable real-time use.

**Changed**
- Reduced noisy display changes.
- Prepared the pipeline for more stable real-time display.

---

## V1.0 — First In-Car Usable Release
*2026-05-19 · versionCode 1*

**Summary**
First usable in-car dashboard build.

**Changed**
- Displayed the initial core FlashPro data set.
- Included basic warning colors and a simplified scale bar.
- Established the 800×480 landscape layout.

**Notes**
- Focus was "can read data stably"; no semantic filtering yet.
