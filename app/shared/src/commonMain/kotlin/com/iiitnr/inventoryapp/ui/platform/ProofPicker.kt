package com.iiitnr.inventoryapp.ui.platform

/** Wraps the existing platform image picker so ViewModels can fake it in tests. */
interface ProofPicker {
    suspend fun pick(): ImageResult?
}

class DefaultProofPicker : ProofPicker {
    override suspend fun pick(): ImageResult? = pickImage()
}
