package com.iiitnr.inventoryapp.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpiUriTest {
    @Test
    fun buildHappyPathProducesExpectedString() {
        assertEquals(
            "upi://pay?pa=mayank@okhdfcbank&pn=IIITNR&am=50&cu=INR",
            UpiUri.build(50, "mayank@okhdfcbank", "IIITNR"),
        )
    }

    @Test
    fun buildOmitsTransactionReference() {
        val uri = UpiUri.build(50, "vpa@bank", "payee")
        assertTrue(!uri.contains("tr="), uri)
    }

    @Test
    fun buildEncodesPayeeSpaces() {
        val uri = UpiUri.build(50, "vpa@bank", "IIITNR Inventory")
        assertTrue(uri.contains("pn=IIITNR%20Inventory"), uri)
    }

    @Test
    fun buildRejectsNonPositiveAmount() {
        assertFailsWith<IllegalArgumentException> { UpiUri.build(0, "vpa@bank", "payee") }
        assertFailsWith<IllegalArgumentException> { UpiUri.build(-5, "vpa@bank", "payee") }
    }

    @Test
    fun buildRejectsBlankFields() {
        assertFailsWith<IllegalArgumentException> { UpiUri.build(10, " ", "payee") }
        assertFailsWith<IllegalArgumentException> { UpiUri.build(10, "vpa@bank", "") }
    }
}
