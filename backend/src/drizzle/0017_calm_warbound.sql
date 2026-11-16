CREATE TYPE "public"."PaymentStatus" AS ENUM('PENDING', 'SUBMITTED', 'VERIFIED', 'REJECTED');--> statement-breakpoint
CREATE TABLE "Payment" (
	"id" text PRIMARY KEY NOT NULL,
	"userId" text NOT NULL,
	"amount" integer NOT NULL,
	"status" "PaymentStatus" DEFAULT 'PENDING' NOT NULL,
	"utr" text,
	"proofImageUrl" text,
	"createdAt" timestamp(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
	"submittedAt" timestamp(3),
	"verifiedAt" timestamp(3),
	"rejectedAt" timestamp(3),
	"verifiedByUserId" text,
	"rejectionReason" text
);
--> statement-breakpoint
CREATE TABLE "PaymentAllocation" (
	"id" text PRIMARY KEY NOT NULL,
	"paymentId" text NOT NULL,
	"requestId" text NOT NULL,
	"amount" integer NOT NULL,
	"createdAt" timestamp(3) DEFAULT CURRENT_TIMESTAMP NOT NULL
);
--> statement-breakpoint
ALTER TABLE "Payment" ADD CONSTRAINT "Payment_userId_fkey" FOREIGN KEY ("userId") REFERENCES "public"."User"("id") ON DELETE restrict ON UPDATE cascade;--> statement-breakpoint
ALTER TABLE "Payment" ADD CONSTRAINT "Payment_verifiedByUserId_fkey" FOREIGN KEY ("verifiedByUserId") REFERENCES "public"."User"("id") ON DELETE restrict ON UPDATE cascade;--> statement-breakpoint
ALTER TABLE "PaymentAllocation" ADD CONSTRAINT "PaymentAllocation_paymentId_fkey" FOREIGN KEY ("paymentId") REFERENCES "public"."Payment"("id") ON DELETE cascade ON UPDATE cascade;--> statement-breakpoint
ALTER TABLE "PaymentAllocation" ADD CONSTRAINT "PaymentAllocation_requestId_fkey" FOREIGN KEY ("requestId") REFERENCES "public"."Request"("id") ON DELETE restrict ON UPDATE cascade;--> statement-breakpoint
CREATE INDEX "Payment_userId_idx" ON "Payment" USING btree ("userId" text_ops);--> statement-breakpoint
CREATE INDEX "Payment_status_idx" ON "Payment" USING btree ("status");--> statement-breakpoint
CREATE INDEX "PaymentAllocation_paymentId_idx" ON "PaymentAllocation" USING btree ("paymentId" text_ops);--> statement-breakpoint
CREATE INDEX "PaymentAllocation_requestId_idx" ON "PaymentAllocation" USING btree ("requestId" text_ops);--> statement-breakpoint
CREATE UNIQUE INDEX "PaymentAllocation_paymentId_requestId_key" ON "PaymentAllocation" USING btree ("paymentId" text_ops,"requestId" text_ops);