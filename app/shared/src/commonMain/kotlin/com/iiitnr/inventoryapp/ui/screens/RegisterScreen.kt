package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.NarrowContentWidth
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit,
    viewModel: AuthViewModel = koinViewModel(),
) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = "Create Account",
                onNavigateBack = onNavigateToLogin,
                maxContentWidth = NarrowContentWidth,
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            RegisterForm(
                viewModel = viewModel,
                onRegisterSuccess = onRegisterSuccess,
                onNavigateToLogin = onNavigateToLogin,
            )
        }
    }
}

/**
 * Centred, width-capped and scrollable form so inputs and the Register button
 * never stretch across a wide desktop window.
 */
@Composable
private fun RegisterForm(
    viewModel: AuthViewModel,
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    val isLoading = viewModel.isLoading

    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Create your account to start requesting components",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )

            RegisterFields(viewModel = viewModel, enabled = !isLoading)

            viewModel.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            Button(
                onClick = {
                    val validationError = registrationValidationError(viewModel.email, viewModel.password)
                    if (validationError != null) {
                        viewModel.errorMessage = validationError
                    } else {
                        viewModel.register(onRegisterSuccess)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Register")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onNavigateToLogin) {
                Text("Back to Role Selection")
            }
        }
    }
}

@Composable
private fun RegisterFields(
    viewModel: AuthViewModel,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = viewModel.name,
        onValueChange = {
            viewModel.name = it
            viewModel.errorMessage = null
        },
        label = { Text("Name (Optional)") },
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        singleLine = true,
        enabled = enabled,
    )

    OutlinedTextField(
        value = viewModel.email,
        onValueChange = {
            viewModel.email = it
            viewModel.errorMessage = null
        },
        label = { Text("Email") },
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        singleLine = true,
        enabled = enabled,
    )

    OutlinedTextField(
        value = viewModel.password,
        onValueChange = {
            viewModel.password = it
            viewModel.errorMessage = null
        },
        label = { Text("Password") },
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        enabled = enabled,
    )
}

/** Client-side guards mirroring the backend rules; `null` means the form is valid. */
private fun registrationValidationError(
    email: String,
    password: String,
): String? =
    when {
        email.isBlank() || password.isBlank() -> "Email and password are required"
        password.length < 8 -> "Password must be at least 8 characters long"
        else -> null
    }
