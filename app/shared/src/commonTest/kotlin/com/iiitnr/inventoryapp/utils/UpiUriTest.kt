package com.iiitnr.inventoryapp.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpiUriTest {
    @Test
    fun buildHappyPathProducesExpectedString() {
        assertEquals(
            "upi://pay?pa=mayank@okhdfcbank&pn=IIITNR&am=50&cu=INR&tr=abc-123",
            UpiUri.build(50, "mayank@okhdfcbank", "IIITNR", "abc-123"),
        )
    }

    @Test
    fun buildEncodesPayeeSpaces() {
        val uri = UpiUri.build(50, "vpa@bank", "IIITNR Inventory", "ref")
        assertTrue(uri.contains("pn=IIITNR%20Inventory"), uri)
    }

    @Test
    fun buildEncodesUnsafeReferenceCharacters() {
        val uri = UpiUri.build(50, "vpa@bank", "payee", "ref 1&x=2")
        assertTrue(uri.contains("tr=ref%201%26x%3D2"), uri)
    }

    @Test
    fun buildRejectsNonPositiveAmount() {
        assertFailsWith<IllegalArgumentException> { UpiUri.build(0, "vpa@bank", "payee", "ref") }
        assertFailsWith<IllegalArgumentException> { UpiUri.build(-5, "vpa@bank", "payee", "ref") }
    }

    @Test
    fun buildRejectsBlankFields() {
        assertFailsWith<IllegalArgumentException> { UpiUri.build(10, " ", "payee", "ref") }
        assertFailsWith<IllegalArgumentException> { UpiUri.build(10, "vpa@bank", "", "ref") }
        assertFailsWith<IllegalArgumentException> { UpiUri.build(10, "vpa@bank", "payee", " ") }
    }
}
