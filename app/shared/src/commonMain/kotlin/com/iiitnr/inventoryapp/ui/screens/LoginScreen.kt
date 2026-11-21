package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.BuildFlags
import com.iiitnr.inventoryapp.data.models.UserRole
import com.iiitnr.inventoryapp.ui.theme.RoleFaculty
import com.iiitnr.inventoryapp.ui.theme.RoleLA
import com.iiitnr.inventoryapp.ui.theme.RoleStudent
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onGoogleSignInClick: ((String?) -> Unit) -> Unit = {},
    viewModel: AuthViewModel = koinViewModel(),
) {
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage
    val passwordFocusRequester = remember { FocusRequester() }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "IIITNR Inventory App",
            style =
                MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Medium,
                ),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Choose how you'd like to sign in",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(40.dp))

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text("Signing you in...", style = MaterialTheme.typography.bodyMedium)
        } else {
            val cardModifier = Modifier.widthIn(max = 400.dp).fillMaxWidth()

            RoleCard(
                title = "Student",
                subtitle = "Browse and request items",
                icon = Icons.Default.School,
                iconBg = RoleStudent.copy(alpha = 0.15f),
                iconTint = RoleStudent,
                onClick = {
                    onGoogleSignInClick { idToken ->
                        if (idToken != null) {
                            viewModel.googleSignIn(idToken, UserRole.STUDENT, onLoginSuccess)
                        }
                    }
                },
                modifier = cardModifier,
            )

            Spacer(modifier = Modifier.height(12.dp))

            RoleCard(
                title = "Faculty",
                subtitle = "Approve and renew requests",
                icon = Icons.Default.Work,
                iconBg = RoleFaculty.copy(alpha = 0.15f),
                iconTint = RoleFaculty,
                onClick = {
                    onGoogleSignInClick { idToken ->
                        if (idToken != null) {
                            viewModel.googleSignIn(idToken, UserRole.FACULTY, onLoginSuccess)
                        }
                    }
                },
                modifier = cardModifier,
            )

            Spacer(modifier = Modifier.height(12.dp))

            RoleCard(
                title = "Lab assistant",
                subtitle = "Issue, return and manage inventory",
                icon = Icons.Default.Build,
                iconBg = RoleLA.copy(alpha = 0.15f),
                iconTint = RoleLA,
                onClick = {
                    onGoogleSignInClick { idToken ->
                        if (idToken != null) {
                            viewModel.googleSignIn(idToken, UserRole.LA, onLoginSuccess)
                        }
                    }
                },
                modifier = cardModifier,
            )

            if (BuildFlags.IS_DEBUG) {
                // Same 400.dp cap as the role cards above, so the debug form does
                // not stretch wider than the sign-in choices.
                Column(modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Text(
                        text = "Debug: Legacy Login",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = viewModel.email,
                        onValueChange = {
                            viewModel.email = it
                            viewModel.errorMessage = null
                        },
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        singleLine = true,
                        enabled = !isLoading,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions =
                            KeyboardActions(
                                onNext = { passwordFocusRequester.requestFocus() },
                            ),
                    )
                    OutlinedTextField(
                        value = viewModel.password,
                        onValueChange = {
                            viewModel.password = it
                            viewModel.errorMessage = null
                        },
                        label = { Text("Password") },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .focusRequester(passwordFocusRequester),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isLoading,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions =
                            KeyboardActions(
                                onDone = { viewModel.login(onLoginSuccess) },
                            ),
                    )
                    Button(
                        onClick = { viewModel.login(onLoginSuccess) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = !isLoading,
                    ) {
                        Text("Login")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onNavigateToRegister,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Go to Register (Debug)")
                    }
                }
            }
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun RoleCard(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
