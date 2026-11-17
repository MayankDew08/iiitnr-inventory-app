# Payment Slice B — manual verification (M1–M8)

> **Status: NOT EXECUTED.** These checks require a real Android device (and optionally iOS).
> Automated tests cover the state machine and rendering; they cannot cover OS UPI intents.

## M1 — Fresh install, no UPI apps
- [ ] Sign in as a student with at least one returned, unpaid fine.
- [ ] Open Fines → tap `Pay ₹X`.
- [ ] Expect the QR fallback screen, not a crash or a blank screen.

## M2 — QR fallback details
- [ ] QR scans from a second device and opens a UPI pay screen with the correct VPA, payee and amount.
- [ ] VPA and reference are selectable/copyable.
- [ ] `I've paid, submit UTR` opens the UTR form.

## M3 — UPI app installed
- [ ] On a device with GPay/PhonePe/Paytm installed, tap `Pay ₹X`.
- [ ] OS app chooser appears (not a silently chosen default app).
- [ ] The chosen UPI app opens with amount `₹X` pre-filled and the correct reference.

## M4 — Return and resume
- [ ] Leave the UPI app without paying, return to the app.
- [ ] Expect `Return here after paying in your UPI app.` plus `I've paid, enter UTR`.
- [ ] Tap it and confirm the payment id and amount are shown.

## M5 — Submit validation
- [ ] Submit with blank UTR → button disabled.
- [ ] Submit without a screenshot → button disabled.
- [ ] Attach a >10 MB image → inline size error, no state change.
- [ ] Attach a `.txt` file → inline format error, no state change.
- [ ] Attach a valid JPG/PNG/WebP/GIF/AVIF and a non-blank UTR → button enabled.

## M6 — Successful submit
- [ ] Submit a valid UTR + screenshot.
- [ ] Expect `Payment submitted` / `Awaiting LA verification.`
- [ ] Re-open Fines: the payment appears under `Awaiting verification` with amount and relative time.

## M7 — Pending and rejected states
- [ ] Seed a PENDING payment via SQL, open Fines: banner `You started a payment of ₹X, … Submit UTR.` appears; Pay button hidden.
- [ ] Tap the banner: UTR form opens for that payment.
- [ ] Seed a REJECTED payment via SQL: `Earlier attempts (N)` section appears, expands, and shows amount, relative time and rejection reason.

## M8 — iOS (if a device is available)
- [ ] Repeat M3–M6 on iOS.
- [ ] If `canOpenURL` returns false for UPI apps, confirm `LSApplicationQueriesSchemes` is present in `iosApp/iosApp/Info.plist`.

## Platform notes (from the plan, recorded here instead of DECISION.md)
- Android 11+ needs the `<queries><intent><action android.intent.action.VIEW /><data android:scheme="upi" /></intent></queries>` entry in `app/android/src/main/AndroidManifest.xml` for `resolveActivity` to see UPI apps. This entry has been added.
- iOS needs `LSApplicationQueriesSchemes` (`tez`, `phonepe`, `paytm`, `upi`) for `canOpenURL` to work. This has been added to `Info.plist`.
- Desktop/JVM and web intentionally return `NoAppAvailable`; the QR fallback is available there.
