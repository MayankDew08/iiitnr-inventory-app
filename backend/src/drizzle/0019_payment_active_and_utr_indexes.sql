ALTER TABLE "Payment" RENAME COLUMN "proofImageUrl" TO "proofFileName";--> statement-breakpoint
CREATE UNIQUE INDEX "Payment_one_active_per_user" ON "Payment" USING btree ("userId" text_ops) WHERE status IN ('PENDING','SUBMITTED');--> statement-breakpoint
CREATE UNIQUE INDEX "Payment_utr_active_key" ON "Payment" USING btree ("utr" text_ops) WHERE status IN ('SUBMITTED','VERIFIED');