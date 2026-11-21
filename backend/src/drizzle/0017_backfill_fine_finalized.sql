-- Requests returned before the freeze marker existed hold a final-looking
-- amount but no marker; stamp it from `returnedAt` so their fines stay payable.
UPDATE "Request"
SET "fineFinalizedAt" = "returnedAt"
WHERE "returnedAt" IS NOT NULL
	AND "lateFeeAccrued" > 0
	AND "fineFinalizedAt" IS NULL;
