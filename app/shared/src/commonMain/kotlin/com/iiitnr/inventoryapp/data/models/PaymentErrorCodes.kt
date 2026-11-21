package com.iiitnr.inventoryapp.data.models

/** Error codes sent by the backend's payment endpoints (UPPER_SNAKE). */
object PaymentErrorCodes {
    const val NO_UNPAID_FINES = "NO_UNPAID_FINES"
    const val PAYMENT_AWAITING_VERIFICATION = "PAYMENT_AWAITING_VERIFICATION"
    const val PAYMENT_NOT_PENDING = "PAYMENT_NOT_PENDING"
    const val INVALID_UTR = "INVALID_UTR"
    const val UTR_ALREADY_USED = "UTR_ALREADY_USED"
    const val UTR_BLOCKED = "UTR_BLOCKED"
}
