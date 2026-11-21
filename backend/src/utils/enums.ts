export const UserRole = {
  ADMIN: 'ADMIN',
  FACULTY: 'FACULTY',
  PENDING: 'PENDING',
  STUDENT: 'STUDENT',
  LA: 'LA',
} as const;

export const RequestStatus = {
  PENDING: 'PENDING',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
  ISSUED: 'ISSUED',
  PARTIALLY_ISSUED: 'PARTIALLY_ISSUED',
  RETURNED: 'RETURNED',
  PARTIALLY_RETURNED: 'PARTIALLY_RETURNED',
  EXPIRED: 'EXPIRED',
  RENEWED: 'RENEWED',
  REQUESTED_RENEW: 'REQUESTED_RENEW',
} as const;

export const ComponentCategory = {
  Sensors: 'Sensors',
  Actuators: 'Actuators',
  Microcontrollers: 'Microcontrollers',
  Microprocessors: 'Microprocessors',
  Others: 'Others',
} as const;

export const Location = {
  IoT_Lab: 'IoT_Lab',
  Robo_Lab: 'Robo_Lab',
  VLSI_Lab: 'VLSI_Lab',
} as const;

export const PaymentRejectionReason = {
  UTR_NOT_FOUND: 'UTR_NOT_FOUND',
  AMOUNT_MISMATCH: 'AMOUNT_MISMATCH',
  PROOF_UNCLEAR: 'PROOF_UNCLEAR',
  DUPLICATE_PAYMENT: 'DUPLICATE_PAYMENT',
  OTHER: 'OTHER',
} as const;

export const AuditActionType = {
  CREATE: 'CREATE',
  UPDATE: 'UPDATE',
  DELETE: 'DELETE',
  LOGIN: 'LOGIN',
  LOGOUT: 'LOGOUT',
  REQUEST_STATUS_CHANGE: 'REQUEST_STATUS_CHANGE',
  INVENTORY_ADJUST: 'INVENTORY_ADJUST',
} as const;

export type UserRoleValue = (typeof UserRole)[keyof typeof UserRole];
export type RequestStatusValue = (typeof RequestStatus)[keyof typeof RequestStatus];
export type CategoryValue = (typeof ComponentCategory)[keyof typeof ComponentCategory];
export type LocationValue = (typeof Location)[keyof typeof Location];
export type AuditActionTypeValue = (typeof AuditActionType)[keyof typeof AuditActionType];
export type PaymentRejectionReasonValue =
  (typeof PaymentRejectionReason)[keyof typeof PaymentRejectionReason];

export const requestStatusValues = Object.values(RequestStatus);
export const categoryValues = Object.values(ComponentCategory);
export const userRoleValues = Object.values(UserRole);
export const paymentRejectionReasonValues = Object.values(PaymentRejectionReason);

const locationEnumValues = Object.values(Location);
export const locationValues = locationEnumValues.map((v) => v.replace(/_/g, ' '));

export const toLocationEnum = (label: string): LocationValue =>
  label.replace(/\s+/g, '_') as LocationValue;
