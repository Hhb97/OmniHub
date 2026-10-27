-- =====================================================================
-- Business / configuration tables
-- Conventions: deleted = 0/1 logical delete, timestamps are timestamptz (UTC)
-- =====================================================================

CREATE TABLE sys_tenant (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sys_user (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES sys_tenant (id),
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(64),
    email         VARCHAR(128),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted       SMALLINT     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_sys_user_username ON sys_user (username) WHERE deleted = 0;
CREATE INDEX idx_sys_user_tenant ON sys_user (tenant_id);

CREATE TABLE sys_role (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES sys_tenant (id),
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255),
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_sys_role_code ON sys_role (tenant_id, code) WHERE deleted = 0;

CREATE TABLE sys_permission (
    code        VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    type        VARCHAR(16) NOT NULL,           -- MENU | API
    parent_code VARCHAR(64),
    sort        INT         NOT NULL DEFAULT 0
);

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL REFERENCES sys_user (id),
    role_id BIGINT NOT NULL REFERENCES sys_role (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sys_role_permission (
    role_id         BIGINT      NOT NULL REFERENCES sys_role (id),
    permission_code VARCHAR(64) NOT NULL REFERENCES sys_permission (code),
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE sys_config (
    config_key  VARCHAR(64) PRIMARY KEY,
    config_value VARCHAR(512) NOT NULL,
    description VARCHAR(255)
);

CREATE TABLE sys_operation_log (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT,
    username   VARCHAR(64),
    module     VARCHAR(64),
    action     VARCHAR(64),
    method     VARCHAR(10),
    path       VARCHAR(255),
    ip         VARCHAR(64),
    success    BOOLEAN,
    cost_ms    BIGINT,
    detail     VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_oplog_created ON sys_operation_log (created_at DESC);

CREATE TABLE sys_login_log (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(64),
    ip         VARCHAR(64),
    success    BOOLEAN,
    message    VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_loginlog_created ON sys_login_log (created_at DESC);

CREATE TABLE iot_product (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES sys_tenant (id),
    name                VARCHAR(128) NOT NULL,
    product_key         VARCHAR(64)  NOT NULL,
    category            VARCHAR(64),
    protocol            VARCHAR(16)  NOT NULL DEFAULT 'MQTT',
    status              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    description         VARCHAR(512),
    thing_model_version INT          NOT NULL DEFAULT 0,
    created_by          VARCHAR(64),
    deleted             SMALLINT     NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_product_key ON iot_product (product_key) WHERE deleted = 0;
CREATE INDEX idx_product_tenant ON iot_product (tenant_id);

-- Thing model definitions live apart from device data. One row per saved version.
CREATE TABLE iot_thing_model (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT      NOT NULL REFERENCES iot_product (id),
    version    INT         NOT NULL,
    content    JSONB       NOT NULL,            -- {properties:[],events:[],services:[]}
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_id, version)
);

CREATE TABLE iot_device (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL REFERENCES sys_tenant (id),
    product_id      BIGINT       NOT NULL REFERENCES iot_product (id),
    name            VARCHAR(128) NOT NULL,
    device_key      VARCHAR(64)  NOT NULL,
    description     VARCHAR(512),
    secret_hash     VARCHAR(64)  NOT NULL,       -- sha256(salt || secret), hex; the secret itself is never stored
    secret_salt     VARCHAR(32)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    mqtt_connected  BOOLEAN      NOT NULL DEFAULT FALSE,   -- transport level: broker connection
    online_status   VARCHAR(16)  NOT NULL DEFAULT 'INACTIVE', -- business level: INACTIVE | ONLINE | OFFLINE
    last_online_at  TIMESTAMPTZ,
    last_offline_at TIMESTAMPTZ,
    last_comm_at    TIMESTAMPTZ,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_device_key ON iot_device (product_id, device_key) WHERE deleted = 0;
CREATE INDEX idx_device_tenant_status ON iot_device (tenant_id, online_status);
CREATE INDEX idx_device_product ON iot_device (product_id);

CREATE TABLE iot_device_command (
    id          BIGSERIAL PRIMARY KEY,
    command_no  VARCHAR(40) NOT NULL UNIQUE,
    tenant_id   BIGINT      NOT NULL,
    device_id   BIGINT      NOT NULL REFERENCES iot_device (id),
    type        VARCHAR(16) NOT NULL,
    identifier  VARCHAR(64),
    params      JSONB,
    status      VARCHAR(16) NOT NULL,
    result      JSONB,
    message     VARCHAR(512),
    created_by  VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ,
    received_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    timeout_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_command_device ON iot_device_command (device_id, created_at DESC);
CREATE INDEX idx_command_pending ON iot_device_command (timeout_at) WHERE status IN ('PENDING', 'SENT', 'RECEIVED');

CREATE TABLE alarm_rule (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL,
    name           VARCHAR(128) NOT NULL,
    scope          VARCHAR(16)  NOT NULL,        -- PRODUCT | DEVICE
    product_id     BIGINT,
    device_id      BIGINT,
    rule_type      VARCHAR(16)  NOT NULL,        -- THRESHOLD | OFFLINE | EVENT
    identifier     VARCHAR(64),
    operator       VARCHAR(8),
    threshold      DOUBLE PRECISION,
    trigger_count  INT          NOT NULL DEFAULT 1,
    silence_seconds INT         NOT NULL DEFAULT 0,
    level          VARCHAR(16)  NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    description    VARCHAR(512),
    deleted        SMALLINT     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_alarm_rule_product ON alarm_rule (product_id) WHERE deleted = 0;
CREATE INDEX idx_alarm_rule_device ON alarm_rule (device_id) WHERE deleted = 0;

CREATE TABLE alarm_record (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL,
    rule_id            BIGINT       NOT NULL,
    device_id          BIGINT       NOT NULL,
    rule_name          VARCHAR(128) NOT NULL,
    rule_type          VARCHAR(16)  NOT NULL,
    level              VARCHAR(16)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,    -- ACTIVE | RECOVERED
    acknowledged       BOOLEAN      NOT NULL DEFAULT FALSE,
    message            VARCHAR(512),
    trigger_value      VARCHAR(255),
    trigger_count      INT          NOT NULL DEFAULT 1,
    first_triggered_at TIMESTAMPTZ  NOT NULL,
    last_triggered_at  TIMESTAMPTZ  NOT NULL,
    recovered_at       TIMESTAMPTZ,
    acknowledged_by    VARCHAR(64),
    acknowledged_at    TIMESTAMPTZ
);
-- At most one ACTIVE alarm per (rule, device): this is what prevents duplicate alarm storms.
CREATE UNIQUE INDEX uk_alarm_active ON alarm_record (rule_id, device_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_alarm_tenant_time ON alarm_record (tenant_id, first_triggered_at DESC);
CREATE INDEX idx_alarm_device ON alarm_record (device_id, first_triggered_at DESC);

CREATE TABLE alarm_action (
    id         BIGSERIAL PRIMARY KEY,
    alarm_id   BIGINT      NOT NULL REFERENCES alarm_record (id) ON DELETE CASCADE,
    action     VARCHAR(16) NOT NULL,             -- TRIGGER | ACK | COMMENT | RECOVER
    operator   VARCHAR(64),
    remark     VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_alarm_action_alarm ON alarm_action (alarm_id, created_at);

-- =====================================================================
-- Device data tables (high write volume). Kept separate from business tables:
-- no foreign keys, append-only, indexed for the access patterns below.
-- A dedicated time-series DB can replace iot_device_property_history behind TelemetryRepository.
-- =====================================================================

-- Latest value per (device, property): upserted on every report, read by the monitor page.
CREATE TABLE iot_device_latest (
    device_id   BIGINT       NOT NULL,
    identifier  VARCHAR(64)  NOT NULL,
    value       JSONB        NOT NULL,
    reported_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (device_id, identifier)
);

CREATE TABLE iot_device_property_history (
    id         BIGSERIAL PRIMARY KEY,
    device_id  BIGINT           NOT NULL,
    identifier VARCHAR(64)      NOT NULL,
    num_value  DOUBLE PRECISION,                -- filled for numeric/boolean(0/1) values, used for aggregation
    value      JSONB            NOT NULL,
    ts         TIMESTAMPTZ      NOT NULL
);
CREATE INDEX idx_prop_hist_dev_id_ts ON iot_device_property_history (device_id, identifier, ts DESC);
CREATE INDEX idx_prop_hist_ts ON iot_device_property_history (ts);

CREATE TABLE iot_device_event (
    id         BIGSERIAL PRIMARY KEY,
    device_id  BIGINT      NOT NULL,
    identifier VARCHAR(64) NOT NULL,
    name       VARCHAR(128),
    level      VARCHAR(16),
    params     JSONB,
    ts         TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_event_device_ts ON iot_device_event (device_id, ts DESC);

CREATE TABLE iot_device_message_log (
    id         BIGSERIAL PRIMARY KEY,
    device_id  BIGINT      NOT NULL,
    direction  VARCHAR(4)  NOT NULL,            -- UP | DOWN
    type       VARCHAR(16) NOT NULL,
    topic      VARCHAR(255),
    payload    VARCHAR(2000),
    result     VARCHAR(16) NOT NULL DEFAULT 'OK',
    message    VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_msglog_device_time ON iot_device_message_log (device_id, created_at DESC);
CREATE INDEX idx_msglog_time ON iot_device_message_log (created_at);
