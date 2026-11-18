package com.iiitnr.inventoryapp.ui.components.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.RequestStatus
import com.iiitnr.inventoryapp.data.models.UserRole
import com.iiitnr.inventoryapp.ui.theme.LocalIsDarkTheme
import com.iiitnr.inventoryapp.ui.theme.inventoryColors

@Composable
fun StatusChip(
    status: RequestStatus,
    modifier: Modifier = Modifier,
) {
    val color = requestStatusColor(status = status)
    val isDark = LocalIsDarkTheme.current

    Surface(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .semantics { contentDescription = "Request status: ${requestStatusLabel(status)}" },
        color = color.copy(alpha = if (isDark) 0.24f else 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = requestStatusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun requestStatusColor(status: RequestStatus): Color {
    val colors = MaterialTheme.inventoryColors
    return when (status) {
        RequestStatus.PENDING -> colors.warning
        RequestStatus.APPROVED -> colors.info
        RequestStatus.ISSUED -> colors.success
        RequestStatus.PARTIALLY_ISSUED -> colors.warning
        RequestStatus.REQUESTED_RENEW -> colors.warning
        RequestStatus.RENEWED -> colors.success
        RequestStatus.RETURNED -> colors.neutral
        RequestStatus.PARTIALLY_RETURNED -> colors.warning
        RequestStatus.EXPIRED -> colors.danger
        RequestStatus.REJECTED -> MaterialTheme.colorScheme.error
    }
}

fun requestStatusLabel(status: RequestStatus): String =
    when (status) {
        RequestStatus.REQUESTED_RENEW -> "Renewal Requested"
        RequestStatus.PARTIALLY_ISSUED -> "Partially Issued"
        RequestStatus.PARTIALLY_RETURNED -> "Partially Returned"
        else ->
            status.name
                .lowercase()
                .split('_')
                .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    }

@Composable
fun userRoleColor(role: UserRole): Color {
    val colors = MaterialTheme.inventoryColors
    return when (role) {
        UserRole.ADMIN -> colors.danger
        UserRole.FACULTY -> colors.info
        UserRole.STUDENT -> colors.success
        UserRole.LA -> colors.warning
        UserRole.PENDING -> colors.pending
    }
}

@Composable
fun auditActionColor(action: String?): Color? {
    val colors = MaterialTheme.inventoryColors
    return when (action?.uppercase()) {
        "CREATE" -> colors.success
        "UPDATE" -> colors.info
        "DELETE" -> colors.danger
        "LOGIN" -> colors.actionPurple
        "LOGOUT" -> colors.neutral
        "REQUEST_STATUS_CHANGE" -> colors.warning
        "INVENTORY_ADJUST" -> colors.actionCyan
        else -> null
    }
}
