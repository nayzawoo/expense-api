CREATE TABLE category (
    id uuid NOT NULL,
    household_id uuid NOT NULL REFERENCES household (id),
    name varchar(120) NOT NULL,
    color varchar(7) NOT NULL,
    icon_key varchar(64) NOT NULL,
    sort_order integer NOT NULL,
    status varchar(16) NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT category_pk PRIMARY KEY (id),
    CONSTRAINT category_household_id_unique UNIQUE (household_id, id),
    CONSTRAINT category_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT category_version_nonnegative CHECK (version >= 0),
    CONSTRAINT category_color_hex CHECK (color ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE UNIQUE INDEX category_name_normalized_unique
    ON category (household_id, lower(btrim(name)));

CREATE INDEX category_household_status_order_idx
    ON category (household_id, status, sort_order, name, id);

CREATE TABLE beneficiary (
    id uuid NOT NULL,
    household_id uuid NOT NULL REFERENCES household (id),
    name varchar(120) NOT NULL,
    kind varchar(16) NOT NULL,
    sort_order integer NOT NULL,
    status varchar(16) NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT beneficiary_pk PRIMARY KEY (id),
    CONSTRAINT beneficiary_household_id_unique UNIQUE (household_id, id),
    CONSTRAINT beneficiary_kind CHECK (kind IN ('PERSON', 'HOUSEHOLD', 'OTHER')),
    CONSTRAINT beneficiary_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT beneficiary_version_nonnegative CHECK (version >= 0)
);

CREATE UNIQUE INDEX beneficiary_name_normalized_unique
    ON beneficiary (household_id, lower(btrim(name)));

CREATE INDEX beneficiary_household_status_order_idx
    ON beneficiary (household_id, status, sort_order, name, id);
