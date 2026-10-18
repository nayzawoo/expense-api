CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    household_id uuid REFERENCES household (id),
    actor_user_id uuid REFERENCES app_user (id),
    event_type varchar(64) NOT NULL,
    resource_type varchar(64) NOT NULL,
    resource_id uuid,
    recorded_at timestamptz NOT NULL,
    request_id varchar(128),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT audit_event_type_nonblank CHECK (char_length(trim(event_type)) > 0),
    CONSTRAINT audit_event_resource_type_nonblank CHECK (char_length(trim(resource_type)) > 0)
);

CREATE INDEX audit_event_household_recorded_idx
    ON audit_event (household_id, recorded_at, id);
