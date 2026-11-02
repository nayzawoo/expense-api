-- Run in one transaction (psql --single-transaction) so failures restore all data.
SET LOCAL lock_timeout = '5s';
LOCK TABLE household IN EXCLUSIVE MODE;
LOCK TABLE account, financial_transaction, transaction_revision, account_posting,
    idempotency_record IN ACCESS EXCLUSIVE MODE;

CREATE TEMP TABLE reset_openings ON COMMIT DROP AS
SELECT t.id, t.household_id, t.created_by_user_id, t.opening_account_id,
       t.created_at, r.recorded_by_user_id, r.actor_display_name_snapshot
FROM financial_transaction t
JOIN transaction_revision r
  ON r.household_id = t.household_id AND r.transaction_id = t.id AND r.revision_no = 1
WHERE t.kind = 'OPENING';

DO $$
BEGIN
    IF (SELECT count(*) FROM reset_openings) <> (SELECT count(*) FROM account) THEN
        RAISE EXCEPTION 'Reset refused: every account must have an opening revision';
    END IF;
END;
$$;

-- Explicit table list: never cascade into accounts or other retained records.
-- Rebuild openings because revisions and postings are append-only.
TRUNCATE account_posting, transaction_revision, financial_transaction;
DELETE FROM idempotency_record;

INSERT INTO financial_transaction (
    id, household_id, kind, created_by_user_id, current_revision_no, version,
    opening_account_id, created_at)
SELECT id, household_id, 'OPENING', created_by_user_id, 1, 0, opening_account_id, created_at
FROM reset_openings;

INSERT INTO transaction_revision (
    household_id, transaction_id, revision_no, operation, state, effective_date,
    amount, currency_code, account_id, recorded_by_user_id, recorded_at,
    account_name_snapshot, actor_display_name_snapshot)
SELECT o.household_id, o.id, 1, 'CREATE', 'POSTED', a.opened_on,
       0, a.currency_code, a.id, o.recorded_by_user_id, CURRENT_TIMESTAMP,
       a.name, o.actor_display_name_snapshot
FROM reset_openings o
JOIN account a ON a.household_id = o.household_id AND a.id = o.opening_account_id;

-- Invalidate stale account/reconciliation versions without reusing old versions.
UPDATE account
SET balance_version = balance_version + 1, version = version + 1, updated_at = CURRENT_TIMESTAMP;
