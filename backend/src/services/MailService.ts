import nodemailer from 'nodemailer';
import type { Transporter } from 'nodemailer';

let transporter: Transporter | null = null;

/**
 * Sending mail is opt-in. The sweeps run wherever the backend runs — including
 * a developer machine with a production-grade mailbox configured — so without
 * an explicit switch they would mail real people.
 */
export function isMailEnabled(): boolean {
  return (process.env.MAIL_ENABLED ?? '').trim().toLowerCase() === 'true';
}

function isDeliverableAddress(address: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(address.trim());
}

export interface MailConfig {
  host: string;
  port: number;
  secure: boolean;
  requireTLS: boolean;
  from: string;
  user: string;
  pass: string;
}

export function resolveMailConfig(): MailConfig | null {
  const host = process.env.SMTP_HOST?.trim();
  const port = Number.parseInt(process.env.SMTP_PORT ?? '', 10);
  const from = process.env.SMTP_FROM?.trim();
  const user = process.env.SMTP_USER?.trim();
  const pass = process.env.SMTP_PASS;

  if (!host || !Number.isFinite(port) || !from || !user || !pass) return null;

  return {
    host,
    port,
    secure: port === 465,
    // On the submission port, insist on STARTTLS so credentials are never sent in the clear.
    requireTLS: port === 587,
    from,
    user,
    pass,
  };
}

export function getMailTransporter(): Transporter | null {
  if (transporter) return transporter;

  const config = resolveMailConfig();
  if (!config) return null;

  transporter = nodemailer.createTransport({
    host: config.host,
    port: config.port,
    secure: config.secure,
    requireTLS: config.requireTLS,
    auth: { user: config.user, pass: config.pass },
  });

  return transporter;
}

/** Test helper: drops the cached transporter so config changes take effect. */
export function resetMailTransporter(): void {
  transporter = null;
}

export async function sendMail(to: string, subject: string, text: string): Promise<boolean> {
  if (!isMailEnabled()) {
    return false;
  }

  if (!isDeliverableAddress(to)) {
    return false;
  }

  const tx = getMailTransporter();
  if (!tx) {
    return false;
  }

  const from = resolveMailConfig()?.from ?? process.env.SMTP_FROM ?? '';
  try {
    await tx.sendMail({ from, to, subject, text });
    return true;
  } catch {
    return false;
  }
}
