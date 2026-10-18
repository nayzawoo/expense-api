CREATE OR REPLACE FUNCTION reject_account_opening_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.opened_on IS DISTINCT FROM OLD.opened_on
        OR NEW.currency_code IS DISTINCT FROM OLD.currency_code THEN
        RAISE EXCEPTION 'account opening fields are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_opening_immutable
    BEFORE UPDATE ON account
    FOR EACH ROW
    EXECUTE FUNCTION reject_account_opening_mutation();

CREATE OR REPLACE FUNCTION reject_opening_head_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.kind = 'OPENING' THEN
        IF NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.opening_account_id IS DISTINCT FROM OLD.opening_account_id
            OR NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id
            OR NEW.current_revision_no IS DISTINCT FROM OLD.current_revision_no THEN
            RAISE EXCEPTION 'opening financial_transaction head is immutable';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER financial_transaction_opening_immutable
    BEFORE UPDATE ON financial_transaction
    FOR EACH ROW
    EXECUTE FUNCTION reject_opening_head_mutation();

CREATE OR REPLACE FUNCTION validate_opening_revision_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    tx_kind varchar(16);
BEGIN
    SELECT kind INTO tx_kind
    FROM financial_transaction
    WHERE household_id = NEW.household_id
      AND id = NEW.transaction_id;
    IF tx_kind = 'OPENING' AND NEW.revision_no <> 1 THEN
        RAISE EXCEPTION 'opening transactions allow only revision 1';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transaction_revision_opening_only_one
    BEFORE INSERT ON transaction_revision
    FOR EACH ROW
    EXECUTE FUNCTION validate_opening_revision_insert();

CREATE OR REPLACE FUNCTION validate_opening_posting_group()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    tx_kind varchar(16);
    opening_account uuid;
    opened_on date;
    currency varchar(3);
    opening_amount numeric;
    posting_count integer;
BEGIN
    SELECT t.kind, t.opening_account_id, a.opened_on, a.currency_code, r.amount
    INTO tx_kind, opening_account, opened_on, currency, opening_amount
    FROM financial_transaction t
    JOIN account a
      ON a.household_id = t.household_id
     AND a.id = t.opening_account_id
    JOIN transaction_revision r
      ON r.household_id = t.household_id
     AND r.transaction_id = t.id
     AND r.revision_no = 1
    WHERE t.household_id = NEW.household_id
      AND t.id = NEW.transaction_id;

    IF tx_kind IS DISTINCT FROM 'OPENING' THEN
        RETURN NULL;
    END IF;

    IF NEW.revision_no <> 1
        OR NEW.role <> 'APPLY'
        OR NEW.account_id IS DISTINCT FROM opening_account
        OR NEW.effective_date IS DISTINCT FROM opened_on
        OR NEW.currency_code IS DISTINCT FROM currency THEN
        RAISE EXCEPTION 'opening posting shape is invalid';
    END IF;

    SELECT count(*) INTO posting_count
    FROM account_posting
    WHERE household_id = NEW.household_id
      AND transaction_id = NEW.transaction_id
      AND revision_no = 1
      AND role = 'APPLY';

    IF opening_amount = 0 THEN
        RAISE EXCEPTION 'zero opening must have no postings';
    END IF;
    IF posting_count <> 1 THEN
        RAISE EXCEPTION 'nonzero opening requires exactly one APPLY posting';
    END IF;
    IF NEW.signed_amount IS DISTINCT FROM opening_amount THEN
        RAISE EXCEPTION 'opening posting amount must match revision';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER account_posting_opening_group
    AFTER INSERT ON account_posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION validate_opening_posting_group();

CREATE OR REPLACE FUNCTION validate_account_opening()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    opening_count integer;
    revision_count integer;
    posting_count integer;
    opening_amount numeric;
BEGIN
    SELECT count(*) INTO opening_count
    FROM financial_transaction
    WHERE household_id = NEW.household_id
      AND kind = 'OPENING'
      AND opening_account_id = NEW.id;
    IF opening_count <> 1 THEN
        RAISE EXCEPTION 'account % requires exactly one opening transaction', NEW.id;
    END IF;

    SELECT count(*) INTO revision_count
    FROM transaction_revision r
    JOIN financial_transaction t
      ON t.household_id = r.household_id
     AND t.id = r.transaction_id
    WHERE t.household_id = NEW.household_id
      AND t.opening_account_id = NEW.id
      AND r.revision_no = 1
      AND r.operation = 'CREATE'
      AND r.state = 'POSTED'
      AND r.account_id = NEW.id
      AND r.effective_date = NEW.opened_on
      AND r.currency_code = NEW.currency_code;
    IF revision_count <> 1 THEN
        RAISE EXCEPTION 'account % requires exactly one opening CREATE revision', NEW.id;
    END IF;

    SELECT r.amount INTO opening_amount
    FROM transaction_revision r
    JOIN financial_transaction t
      ON t.household_id = r.household_id
     AND t.id = r.transaction_id
    WHERE t.household_id = NEW.household_id
      AND t.opening_account_id = NEW.id
      AND r.revision_no = 1;

    SELECT count(*) INTO posting_count
    FROM account_posting p
    JOIN financial_transaction t
      ON t.household_id = p.household_id
     AND t.id = p.transaction_id
    WHERE t.household_id = NEW.household_id
      AND t.opening_account_id = NEW.id
      AND p.revision_no = 1
      AND p.role = 'APPLY';

    IF opening_amount = 0 AND posting_count <> 0 THEN
        RAISE EXCEPTION 'zero opening for account % must have no postings', NEW.id;
    END IF;
    IF opening_amount <> 0 AND posting_count <> 1 THEN
        RAISE EXCEPTION 'nonzero opening for account % requires one APPLY posting', NEW.id;
    END IF;
    IF opening_amount <> 0 THEN
        IF NOT EXISTS (
            SELECT 1
            FROM account_posting p
            JOIN financial_transaction t
              ON t.household_id = p.household_id
             AND t.id = p.transaction_id
            WHERE t.household_id = NEW.household_id
              AND t.opening_account_id = NEW.id
              AND p.revision_no = 1
              AND p.role = 'APPLY'
              AND p.leg_no = 1
              AND p.account_id = NEW.id
              AND p.effective_date = NEW.opened_on
              AND p.currency_code = NEW.currency_code
              AND p.signed_amount = opening_amount
        ) THEN
            RAISE EXCEPTION 'opening posting amount must match revision for account %', NEW.id;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
