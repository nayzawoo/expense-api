ALTER TABLE app_user
    ADD CONSTRAINT app_user_household_id_unique UNIQUE (household_id, id);

CREATE OR REPLACE FUNCTION money_is_whole(value numeric)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT value IS NOT NULL
        AND value::text NOT IN ('NaN', 'Infinity', '-Infinity')
        AND COALESCE(scale(value) = 0, FALSE);
$$;

CREATE TABLE account (
    id uuid NOT NULL,
    household_id uuid NOT NULL REFERENCES household (id),
    name varchar(120) NOT NULL,
    owner_label varchar(120) NOT NULL,
    type varchar(16) NOT NULL,
    currency_code varchar(3) NOT NULL,
    opened_on date NOT NULL,
    status varchar(16) NOT NULL,
    sort_order integer NOT NULL,
    version bigint NOT NULL,
    balance_version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT account_pk PRIMARY KEY (id),
    CONSTRAINT account_household_id_unique UNIQUE (household_id, id),
    CONSTRAINT account_type CHECK (type IN ('BANK', 'WALLET', 'CASH')),
    CONSTRAINT account_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT account_currency_mmk CHECK (currency_code = 'MMK'),
    CONSTRAINT account_version_nonnegative CHECK (version >= 0),
    CONSTRAINT account_balance_version_nonnegative CHECK (balance_version >= 0)
);

CREATE TABLE financial_transaction (
    id uuid NOT NULL,
    household_id uuid NOT NULL,
    kind varchar(16) NOT NULL,
    created_by_user_id uuid NOT NULL,
    current_revision_no integer,
    version bigint NOT NULL,
    opening_account_id uuid,
    created_at timestamptz NOT NULL,
    CONSTRAINT financial_transaction_pk PRIMARY KEY (id),
    CONSTRAINT financial_transaction_household_id_unique UNIQUE (household_id, id),
    CONSTRAINT financial_transaction_household_fk FOREIGN KEY (household_id) REFERENCES household (id),
    CONSTRAINT financial_transaction_actor_fk
        FOREIGN KEY (household_id, created_by_user_id) REFERENCES app_user (household_id, id),
    CONSTRAINT financial_transaction_opening_account_fk
        FOREIGN KEY (household_id, opening_account_id) REFERENCES account (household_id, id),
    CONSTRAINT financial_transaction_opening_unique UNIQUE (household_id, opening_account_id),
    CONSTRAINT financial_transaction_kind CHECK (
        kind IN ('OPENING', 'EXPENSE', 'INCOME', 'TRANSFER', 'ADJUSTMENT')
    ),
    CONSTRAINT financial_transaction_opening_shape CHECK (
        (kind = 'OPENING' AND opening_account_id IS NOT NULL)
        OR (kind <> 'OPENING' AND opening_account_id IS NULL)
    ),
    CONSTRAINT financial_transaction_version_nonnegative CHECK (version >= 0),
    CONSTRAINT financial_transaction_revision_positive CHECK (
        current_revision_no IS NULL OR current_revision_no >= 1
    )
);

CREATE TABLE transaction_revision (
    household_id uuid NOT NULL,
    transaction_id uuid NOT NULL,
    revision_no integer NOT NULL,
    operation varchar(16) NOT NULL,
    state varchar(16) NOT NULL,
    previous_revision_no integer,
    effective_date date NOT NULL,
    amount numeric NOT NULL,
    currency_code varchar(3) NOT NULL,
    account_id uuid,
    source_account_id uuid,
    destination_account_id uuid,
    category_id uuid,
    beneficiary_id uuid,
    note varchar(2000),
    change_reason varchar(500),
    recorded_by_user_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    account_name_snapshot varchar(120),
    actor_display_name_snapshot varchar(200) NOT NULL,
    CONSTRAINT transaction_revision_pk PRIMARY KEY (household_id, transaction_id, revision_no),
    CONSTRAINT transaction_revision_head_fk
        FOREIGN KEY (household_id, transaction_id) REFERENCES financial_transaction (household_id, id),
    CONSTRAINT transaction_revision_account_fk
        FOREIGN KEY (household_id, account_id) REFERENCES account (household_id, id),
    CONSTRAINT transaction_revision_actor_fk
        FOREIGN KEY (household_id, recorded_by_user_id) REFERENCES app_user (household_id, id),
    CONSTRAINT transaction_revision_operation CHECK (operation IN ('CREATE', 'REPLACE', 'VOID')),
    CONSTRAINT transaction_revision_state CHECK (state IN ('POSTED', 'VOID')),
    CONSTRAINT transaction_revision_no_positive CHECK (revision_no >= 1),
    CONSTRAINT transaction_revision_amount_whole CHECK (money_is_whole(amount)),
    CONSTRAINT transaction_revision_amount_bound CHECK (
        amount BETWEEN -99999999999999999 AND 99999999999999999
    ),
    CONSTRAINT transaction_revision_currency_mmk CHECK (currency_code = 'MMK')
);

ALTER TABLE financial_transaction
    ADD CONSTRAINT financial_transaction_current_revision_fk
    FOREIGN KEY (household_id, id, current_revision_no)
    REFERENCES transaction_revision (household_id, transaction_id, revision_no)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE account_posting (
    id uuid NOT NULL,
    household_id uuid NOT NULL,
    transaction_id uuid NOT NULL,
    revision_no integer NOT NULL,
    account_id uuid NOT NULL,
    effective_date date NOT NULL,
    currency_code varchar(3) NOT NULL,
    signed_amount numeric NOT NULL,
    leg_no integer NOT NULL,
    role varchar(16) NOT NULL,
    reverses_posting_id uuid,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT account_posting_pk PRIMARY KEY (id),
    CONSTRAINT account_posting_household_id_unique UNIQUE (household_id, id),
    CONSTRAINT account_posting_revision_fk
        FOREIGN KEY (household_id, transaction_id, revision_no)
        REFERENCES transaction_revision (household_id, transaction_id, revision_no),
    CONSTRAINT account_posting_account_fk
        FOREIGN KEY (household_id, account_id) REFERENCES account (household_id, id),
    CONSTRAINT account_posting_reversal_fk
        FOREIGN KEY (household_id, reverses_posting_id) REFERENCES account_posting (household_id, id),
    CONSTRAINT account_posting_leg_unique UNIQUE (household_id, transaction_id, revision_no, role, leg_no),
    CONSTRAINT account_posting_reversal_unique UNIQUE (reverses_posting_id),
    CONSTRAINT account_posting_role CHECK (role IN ('APPLY', 'REVERSE')),
    CONSTRAINT account_posting_leg_positive CHECK (leg_no >= 1),
    CONSTRAINT account_posting_amount_whole CHECK (money_is_whole(signed_amount)),
    CONSTRAINT account_posting_amount_nonzero CHECK (signed_amount <> 0),
    CONSTRAINT account_posting_amount_bound CHECK (
        signed_amount BETWEEN -99999999999999999 AND 99999999999999999
    ),
    CONSTRAINT account_posting_currency_mmk CHECK (currency_code = 'MMK'),
    CONSTRAINT account_posting_reverse_shape CHECK (
        (role = 'APPLY' AND reverses_posting_id IS NULL)
        OR (role = 'REVERSE' AND reverses_posting_id IS NOT NULL)
    )
);

CREATE TABLE idempotency_record (
    id uuid NOT NULL,
    household_id uuid NOT NULL REFERENCES household (id),
    actor_user_id uuid NOT NULL,
    operation varchar(200) NOT NULL,
    key varchar(100) NOT NULL,
    request_hash varchar(64) NOT NULL,
    http_status integer NOT NULL,
    resource_id uuid,
    resource_version bigint,
    response_body text NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT idempotency_record_pk PRIMARY KEY (id),
    CONSTRAINT idempotency_record_actor_fk
        FOREIGN KEY (household_id, actor_user_id) REFERENCES app_user (household_id, id),
    CONSTRAINT idempotency_record_unique UNIQUE (household_id, actor_user_id, operation, key),
    CONSTRAINT idempotency_record_key_length CHECK (char_length(key) BETWEEN 1 AND 100)
);

CREATE INDEX account_posting_balance_idx
    ON account_posting (household_id, account_id, effective_date, id);

CREATE OR REPLACE FUNCTION reject_revision_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'transaction_revision is append-only';
END;
$$;

CREATE TRIGGER transaction_revision_no_update
    BEFORE UPDATE OR DELETE ON transaction_revision
    FOR EACH ROW
    EXECUTE FUNCTION reject_revision_mutation();

CREATE OR REPLACE FUNCTION reject_posting_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'account_posting is append-only';
END;
$$;

CREATE TRIGGER account_posting_no_update
    BEFORE UPDATE OR DELETE ON account_posting
    FOR EACH ROW
    EXECUTE FUNCTION reject_posting_mutation();

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
      AND p.role = 'APPLY'
      AND p.leg_no = 1
      AND p.account_id = NEW.id
      AND p.effective_date = NEW.opened_on
      AND p.currency_code = NEW.currency_code;

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
              AND p.signed_amount = opening_amount
        ) THEN
            RAISE EXCEPTION 'opening posting amount must match revision for account %', NEW.id;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER account_opening_complete
    AFTER INSERT ON account
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION validate_account_opening();
