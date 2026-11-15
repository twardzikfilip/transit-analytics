CREATE TABLE user_role (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(32) NOT NULL UNIQUE
);

INSERT INTO user_role (name) VALUES ('ADMIN'), ('GUEST');

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role_id       BIGINT       NOT NULL REFERENCES user_role (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE route (
    id         BIGSERIAL PRIMARY KEY,
    short_name VARCHAR(32)  NOT NULL,
    long_name  VARCHAR(255),
    type       VARCHAR(32)  NOT NULL,
    color      VARCHAR(7)
);

CREATE TABLE stop (
    id        BIGSERIAL PRIMARY KEY,
    code      VARCHAR(32),
    name      VARCHAR(255)     NOT NULL,
    latitude  DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL
);

CREATE TABLE vehicle (
    id             BIGSERIAL PRIMARY KEY,
    vehicle_number VARCHAR(32) NOT NULL UNIQUE,
    type           VARCHAR(32) NOT NULL,
    capacity       INTEGER,
    active         BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE TABLE trip (
    id         BIGSERIAL PRIMARY KEY,
    route_id   BIGINT       NOT NULL REFERENCES route (id) ON DELETE CASCADE,
    vehicle_id BIGINT REFERENCES vehicle (id) ON DELETE SET NULL,
    headsign   VARCHAR(255),
    direction  SMALLINT,
    start_time TIMESTAMPTZ,
    end_time   TIMESTAMPTZ
);

CREATE INDEX idx_trip_route ON trip (route_id);

CREATE TABLE vehicle_position (
    id          BIGSERIAL PRIMARY KEY,
    vehicle_id  BIGINT           NOT NULL REFERENCES vehicle (id) ON DELETE CASCADE,
    trip_id     BIGINT REFERENCES trip (id) ON DELETE SET NULL,
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    bearing     DOUBLE PRECISION,
    speed       DOUBLE PRECISION,
    recorded_at TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX idx_vehicle_position_vehicle_time ON vehicle_position (vehicle_id, recorded_at DESC);
