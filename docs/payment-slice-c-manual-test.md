# Payment Slice C — manual verification (MC1–MC5)

> **Status: NOT EXECUTED.** These checks require a real device and two LA sessions.
> Automated tests cover the state machine, endpoint guards and settlement; they cannot
> cover image rendering, cross-device notifications or concurrent human actions.

## MC1 — LA pending list
- [ ] Sign in as an LA (or Admin).
- [ ] Open Profile → `Payment Verifications`.
- [ ] Expect only `SUBMITTED` payments, oldest submission first, with the correct student name/email, amount, UTR and relative submission time.
- [ ] Confirm VERIFIED and REJECTED payments are absent.
- [ ] Sign in as a student and confirm the `Payment Verifications` button is not shown on Profile.

## MC2 — Detail, proof and allocations
- [ ] From the pending list, open a payment.
- [ ] Expect the student name/email, amount, UTR and submission time.
- [ ] Expect the proof image to render; tap it to enlarge full-screen and tap again to dismiss.
- [ ] Expect the allocated fines table to list each request's project title, fine amount and component names × quantities.
- [ ] If the proof URL is missing/unreachable, expect a placeholder or error message rather than a crash.

## MC3 — Verify settles fines end-to-end
- [ ] As LA, verify a payment with one or more allocated fines.
- [ ] Expect the `Payment resolved` confirmation and a `Back to verifications` button.
- [ ] Expect the payment to be gone from the pending list (tap `Refresh` if needed).
- [ ] On the student device, reopen Fines: the fines covered by this payment show as settled (`fineSettledAt` set, no longer counted as unpaid).
- [ ] The student receives a `Payment verified` notification.
- [ ] In the DB, confirm the Payment is `VERIFIED` with `verifiedAt` and `verifiedByUserId`, all allocated Requests share one `fineSettledAt`, and one `AuditLog` row (`UPDATE`, entity `Payment`) exists.

## MC4 — Reject keeps fines unpaid
- [ ] As LA, open a different payment, tap `Reject payment`.
- [ ] Try submitting without selecting a reason → blocked, inline error.
- [ ] Select `UTR not found in bank records`, add notes, and submit.
- [ ] Expect the `Payment resolved` confirmation; the payment disappears from the pending list.
- [ ] The student receives a `Payment rejected` notification containing the reason and the notes.
- [ ] The student can start a new payment for the same still-unpaid fines.
- [ ] In the DB, confirm `status = REJECTED`, `rejectionReasonCode = UTR_NOT_FOUND`, `rejectionReason` holds the notes, `fineSettledAt` is still null on the allocated Requests, and one `AuditLog` row exists.

## MC5 — Concurrent verify (two LAs / two tabs)
- [ ] Seed one `SUBMITTED` payment with a single allocation.
- [ ] On two devices (or two browser tabs), both LAs open the same payment detail.
- [ ] Both tap `Verify payment` at approximately the same time.
- [ ] Expect exactly one to reach `Payment resolved`; the other sees the `This payment was already resolved by another LA.` error.
- [ ] In the DB, confirm the Payment is `VERIFIED` exactly once, the Request's `fineSettledAt` is set once, and only one `AuditLog` verify row and one `Payment verified` notification exist.

## Notes
- There is no undo endpoint by design. Fixing a mistaken verification requires a manual DB change by an admin.
- Run `docs/payment-slice-b-manual-test.md` (M1–M8) first if the student-side submit flow has not been verified yet.
- Android build was not available in the implementation environment (`SDK location not found`); build the debug APK in an SDK-enabled environment before running these checks.
