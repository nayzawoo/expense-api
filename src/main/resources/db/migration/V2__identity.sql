CREATE TABLE household (
    id uuid PRIMARY KEY,
    name varchar(200) NOT NULL,
    currency varchar(3) NOT NULL,
    currency_scale smallint NOT NULL,
    timezone varchar(64) NOT NULL,
    singleton boolean NOT NULL DEFAULT true,
    CONSTRAINT household_singleton_true CHECK (singleton),
    CONSTRAINT household_one_row UNIQUE (singleton),
    CONSTRAINT household_currency_mmk CHECK (currency = 'MMK'),
    CONSTRAINT household_scale_zero CHECK (currency_scale = 0),
    CONSTRAINT household_timezone CHECK (timezone = 'Asia/Yangon')
);

CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    household_id uuid NOT NULL REFERENCES household (id),
    email varchar(320) NOT NULL,
    display_name varchar(200) NOT NULL,
    password_hash varchar(100) NOT NULL,
    role varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    must_change_password boolean NOT NULL,
    password_changed_at timestamptz,
    security_version bigint NOT NULL,
    CONSTRAINT app_user_email_unique UNIQUE (email),
    CONSTRAINT app_user_role CHECK (role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT app_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT app_user_security_version_nonnegative CHECK (security_version >= 0)
);

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
