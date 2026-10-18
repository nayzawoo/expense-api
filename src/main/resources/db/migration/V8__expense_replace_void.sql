CREATE OR REPLACE FUNCTION validate_expense_mutation_posting_group()
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
    previous_no integer;
    prior_apply_count integer;
    reverse_count integer;
    apply_count integer;
    unmatched integer;
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
           r.operation, r.state, r.previous_revision_no
    INTO revision_amount, revision_account, revision_category, revision_beneficiary, revision_date,
         revision_currency, revision_operation, revision_state, previous_no
    FROM transaction_revision r
    WHERE r.household_id = NEW.household_id
      AND r.transaction_id = NEW.transaction_id
      AND r.revision_no = NEW.revision_no;

    IF revision_operation IS DISTINCT FROM 'REPLACE'
        AND revision_operation IS DISTINCT FROM 'VOID' THEN
        RETURN NULL;
    END IF;

    IF previous_no IS NULL OR previous_no <> (NEW.revision_no - 1) THEN
        RAISE EXCEPTION 'expense mutation revision linkage is invalid';
    END IF;

    IF revision_operation = 'VOID' THEN
        IF revision_state IS DISTINCT FROM 'VOID'
            OR revision_amount IS NULL
            OR revision_amount <= 0
            OR revision_account IS NULL
            OR revision_category IS NULL
            OR revision_beneficiary IS NULL THEN
            RAISE EXCEPTION 'expense void revision shape is invalid';
        END IF;
    ELSIF revision_state IS DISTINCT FROM 'POSTED'
        OR revision_amount IS NULL
        OR revision_amount <= 0
        OR revision_account IS NULL
        OR revision_category IS NULL
        OR revision_beneficiary IS NULL THEN
        RAISE EXCEPTION 'expense replace revision shape is invalid';
    END IF;

    SELECT count(*)
    INTO prior_apply_count
    FROM account_posting
    WHERE household_id = NEW.household_id
      AND transaction_id = NEW.transaction_id
      AND revision_no = previous_no
      AND role = 'APPLY';

    SELECT count(*)
    INTO reverse_count
    FROM account_posting
    WHERE household_id = NEW.household_id
      AND transaction_id = NEW.transaction_id
      AND revision_no = NEW.revision_no
      AND role = 'REVERSE';

    IF reverse_count IS DISTINCT FROM prior_apply_count OR prior_apply_count < 1 THEN
        RAISE EXCEPTION 'expense mutation reverse group is invalid';
    END IF;

    SELECT count(*)
    INTO unmatched
    FROM account_posting prior
    WHERE prior.household_id = NEW.household_id
      AND prior.transaction_id = NEW.transaction_id
      AND prior.revision_no = previous_no
      AND prior.role = 'APPLY'
      AND NOT EXISTS (
          SELECT 1
          FROM account_posting rev
          WHERE rev.household_id = prior.household_id
            AND rev.transaction_id = prior.transaction_id
            AND rev.revision_no = NEW.revision_no
            AND rev.role = 'REVERSE'
            AND rev.reverses_posting_id = prior.id
            AND rev.account_id = prior.account_id
            AND rev.effective_date = prior.effective_date
            AND rev.currency_code = prior.currency_code
            AND rev.signed_amount = (-prior.signed_amount)
      );

    IF unmatched <> 0 THEN
        RAISE EXCEPTION 'expense mutation reverse group is invalid';
    END IF;

    SELECT count(*)
    INTO apply_count
    FROM account_posting
    WHERE household_id = NEW.household_id
      AND transaction_id = NEW.transaction_id
      AND revision_no = NEW.revision_no
      AND role = 'APPLY';

    IF revision_operation = 'VOID' THEN
        IF apply_count <> 0 THEN
            RAISE EXCEPTION 'expense void posting group is invalid';
        END IF;
    ELSE
        IF apply_count <> 1 THEN
            RAISE EXCEPTION 'expense replace posting group is invalid';
        END IF;
        IF NOT EXISTS (
            SELECT 1
            FROM account_posting
            WHERE household_id = NEW.household_id
              AND transaction_id = NEW.transaction_id
              AND revision_no = NEW.revision_no
              AND role = 'APPLY'
              AND account_id = revision_account
              AND effective_date = revision_date
              AND currency_code = revision_currency
              AND signed_amount = (-revision_amount)
              AND reverses_posting_id IS NULL
        ) THEN
            RAISE EXCEPTION 'expense replace posting group is invalid';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER account_posting_expense_mutation_group
    AFTER INSERT ON account_posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION validate_expense_mutation_posting_group();
