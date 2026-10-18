ALTER TABLE transaction_revision
    ADD COLUMN category_name_snapshot varchar(120),
    ADD COLUMN beneficiary_name_snapshot varchar(120);

ALTER TABLE transaction_revision
    ADD CONSTRAINT transaction_revision_category_fk
        FOREIGN KEY (household_id, category_id) REFERENCES category (household_id, id),
    ADD CONSTRAINT transaction_revision_beneficiary_fk
        FOREIGN KEY (household_id, beneficiary_id) REFERENCES beneficiary (household_id, id);

CREATE OR REPLACE FUNCTION validate_expense_create_posting_group()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    tx_kind varchar(16);
    revision_amount numeric;
    revision_account uuid;
    revision_category uuid;
    revision_beneficiary uuid;
    revision_date date;
    revision_currency varchar(3);
    revision_operation varchar(16);
    revision_state varchar(16);
    posting_count integer;
    apply_amount numeric;
    apply_account uuid;
    apply_date date;
    apply_currency varchar(3);
BEGIN
    SELECT t.kind
    INTO tx_kind
    FROM financial_transaction t
    WHERE t.household_id = NEW.household_id
      AND t.id = NEW.transaction_id;

    IF tx_kind IS DISTINCT FROM 'EXPENSE' THEN
        RETURN NULL;
    END IF;

    SELECT r.amount, r.account_id, r.category_id, r.beneficiary_id, r.effective_date, r.currency_code,
           r.operation, r.state
    INTO revision_amount, revision_account, revision_category, revision_beneficiary, revision_date,
         revision_currency, revision_operation, revision_state
    FROM transaction_revision r
    WHERE r.household_id = NEW.household_id
      AND r.transaction_id = NEW.transaction_id
      AND r.revision_no = NEW.revision_no;

    IF revision_operation IS DISTINCT FROM 'CREATE'
        OR revision_state IS DISTINCT FROM 'POSTED'
        OR NEW.revision_no <> 1 THEN
        RETURN NULL;
    END IF;

    IF revision_amount IS NULL
        OR revision_amount <= 0
        OR revision_account IS NULL
        OR revision_category IS NULL
        OR revision_beneficiary IS NULL THEN
        RAISE EXCEPTION 'expense create revision shape is invalid';
    END IF;

    SELECT count(*), min(signed_amount), min(account_id), min(effective_date), min(currency_code)
    INTO posting_count, apply_amount, apply_account, apply_date, apply_currency
    FROM account_posting
    WHERE household_id = NEW.household_id
      AND transaction_id = NEW.transaction_id
      AND revision_no = NEW.revision_no
      AND role = 'APPLY';

    IF posting_count <> 1
        OR apply_amount IS DISTINCT FROM (-revision_amount)
        OR apply_account IS DISTINCT FROM revision_account
        OR apply_date IS DISTINCT FROM revision_date
        OR apply_currency IS DISTINCT FROM revision_currency THEN
        RAISE EXCEPTION 'expense create posting group is invalid';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM account_posting
        WHERE household_id = NEW.household_id
          AND transaction_id = NEW.transaction_id
          AND revision_no = NEW.revision_no
          AND role <> 'APPLY'
    ) THEN
        RAISE EXCEPTION 'expense create posting group is invalid';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER account_posting_expense_create_group
    AFTER INSERT ON account_posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION validate_expense_create_posting_group();
