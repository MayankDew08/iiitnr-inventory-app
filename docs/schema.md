# Database Schema & Entities

The PostgreSQL schema is powered by [Drizzle ORM](https://orm.drizzle.team/). The
diagram below is **generated from `backend/src/drizzle/schema.ts`** — regenerate it
with:

```bash
just gen-schema
```

> [!WARNING]
> This file is generated. Do not edit it by hand — change the schema in
> `backend/src/drizzle/schema.ts` and run the generator again. CI fails if this
> page is out of date.

```mermaid
erDiagram
    User {
        text id PK
        text email UK
        text name
        text imageUrl
        text passwordHash
        text googleId UK
        UserRole role "ADMIN|FACULTY|PENDING|STUDENT|LA"
        text batch
        text branch
        text createdAt
        text updatedAt
    }
    Request {
        text id PK
        text userId FK
        text targetFacultyId FK
        text projectTitle
        RequestStatus status "PENDING|APPROVED|REJECTED|ISSUED|PARTIALLY_ISSUED|RETURNED|PARTIALLY_RETURNED|EXPIRED|RENEWED|REQUESTED_RENEW"
        text createdAt
        text updatedAt
        text returnDueAt
        text returnedAt
        text fulfilledAt
        text receivedByUserId
        text lastRenewDate
        text lastRenewReason
        text renewalReminderSentAt
        text renewalRequestedAt
        integer lateFeeAccrued
    }
    RequestItem {
        text id PK
        text requestId FK
        text componentId FK
        integer quantity
        integer fulfilledQuantity
        integer returnedQuantity
        integer writtenOffQuantity
        text createdAt
        text updatedAt
    }
    Component {
        text id PK
        text name
        text description
        text imageUrl
        integer totalQuantity
        integer availableQuantity
        ComponentCategory category "Sensors|Actuators|Microcontrollers|Microprocessors|Others"
        Location location "IoT_Lab|Robo_Lab|VLSI_Lab"
        text createdAt
        text updatedAt
    }
    AuditLog {
        text id PK
        text userId FK
        AuditActionType action "CREATE|UPDATE|DELETE|LOGIN|LOGOUT|REQUEST_STATUS_CHANGE|INVENTORY_ADJUST"
        text entityType
        text entityId
        text oldValues
        text newValues
        varchar ipAddress
        text userAgent
        text metadata
        text createdAt
    }
    Notification {
        text id PK
        text recipientId FK
        text type
        text title
        text body
        text requestId FK
        boolean read
        text createdAt
    }
    RequestEmail {
        text id PK
        text requestId FK
        text recipientId FK
        RequestEmailKind kind "RENEWAL_CHASE|EXPIRY_NOTICE|LATE_FEE|RENEWAL_REMINDER"
        text variant
        text subject
        text metadata
        text sentAt
    }
    User ||--o{ Request : "supervises"
    User ||--o{ Request : "submits"
    Component ||--o{ RequestItem : "included_in"
    Request ||--o{ RequestItem : "contains"
    User ||--o{ AuditLog : "triggers"
    User ||--o{ Notification : "notified"
    Request ||--o{ Notification : "notifies about"
    Request ||--o{ RequestEmail : "emailed for"
    User ||--o{ RequestEmail : "emailed"
```

## Notes

- The legacy `_prisma_migrations` table is excluded: it is a leftover from the
  Prisma era and is not part of the application domain.
- `writtenOffQuantity` on `RequestItem` tracks stock that was issued but never
  returned before a request expired. It is subtracted from `Component.totalQuantity`
  but not from `availableQuantity` (the stock was already deducted at issue time).
- `renewalReminderSentAt` on `Request` guarantees the 7-day renewal reminder
  email is sent exactly once per request.
- `Notification` rows are the in-app notification feed; `read` drives the unread
  badge in the client.
