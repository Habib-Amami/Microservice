-- Axon JPA storage tables.
--
-- With Axon Server enabled (Compose) the event store/token store live in
-- Axon Server, but Axon's Spring Boot JPA auto-configuration still registers
-- these @Entity classes. With ddl-auto=validate Hibernate therefore expects
-- the tables to exist. The host run (Axon Server disabled) uses the JPA event
-- store and needs them for real. Keeping them in Flyway makes both modes work.

CREATE TABLE association_value_entry_seq (next_val BIGINT) ENGINE=InnoDB;
INSERT INTO association_value_entry_seq (next_val) VALUES (1);

CREATE TABLE association_value_entry (
    id BIGINT NOT NULL,
    association_key VARCHAR(255) NOT NULL,
    association_value VARCHAR(255),
    saga_id VARCHAR(255) NOT NULL,
    saga_type VARCHAR(255),
    PRIMARY KEY (id)
) ENGINE=InnoDB;
CREATE INDEX ix_axon_assoc_lookup ON association_value_entry (saga_type, association_key, association_value);
CREATE INDEX ix_axon_assoc_saga ON association_value_entry (saga_id, saga_type);

CREATE TABLE domain_event_entry_seq (next_val BIGINT) ENGINE=InnoDB;
INSERT INTO domain_event_entry_seq (next_val) VALUES (1);

CREATE TABLE domain_event_entry (
    global_index BIGINT NOT NULL,
    sequence_number BIGINT NOT NULL,
    aggregate_identifier VARCHAR(255) NOT NULL,
    event_identifier VARCHAR(255) NOT NULL,
    payload_revision VARCHAR(255),
    payload_type VARCHAR(255) NOT NULL,
    time_stamp VARCHAR(255) NOT NULL,
    type VARCHAR(255),
    meta_data BLOB,
    payload BLOB NOT NULL,
    PRIMARY KEY (global_index)
) ENGINE=InnoDB;
CREATE UNIQUE INDEX uk_axon_domain_aggregate ON domain_event_entry (aggregate_identifier, sequence_number);
CREATE UNIQUE INDEX uk_axon_domain_event ON domain_event_entry (event_identifier);

CREATE TABLE snapshot_event_entry (
    sequence_number BIGINT NOT NULL,
    aggregate_identifier VARCHAR(255) NOT NULL,
    event_identifier VARCHAR(255) NOT NULL,
    payload_revision VARCHAR(255),
    payload_type VARCHAR(255) NOT NULL,
    time_stamp VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    meta_data BLOB,
    payload BLOB NOT NULL,
    PRIMARY KEY (sequence_number, aggregate_identifier, type)
) ENGINE=InnoDB;
CREATE UNIQUE INDEX uk_axon_snapshot_event ON snapshot_event_entry (event_identifier);

CREATE TABLE saga_entry (
    saga_id VARCHAR(255) NOT NULL,
    saga_type VARCHAR(255),
    revision VARCHAR(255),
    serialized_saga BLOB,
    PRIMARY KEY (saga_id)
) ENGINE=InnoDB;

CREATE TABLE token_entry (
    segment INT NOT NULL,
    owner VARCHAR(255),
    processor_name VARCHAR(255) NOT NULL,
    timestamp VARCHAR(255) NOT NULL,
    token_type VARCHAR(255),
    token BLOB,
    PRIMARY KEY (segment, processor_name)
) ENGINE=InnoDB;

CREATE TABLE dead_letter_entry (
    enqueued_at DATETIME(6) NOT NULL,
    last_touched DATETIME(6),
    processing_started DATETIME(6),
    sequence_index BIGINT NOT NULL,
    sequence_number BIGINT,
    cause_message VARCHAR(1023),
    aggregate_identifier VARCHAR(255),
    cause_type VARCHAR(255),
    dead_letter_id VARCHAR(255) NOT NULL,
    event_identifier VARCHAR(255) NOT NULL,
    message_type VARCHAR(255) NOT NULL,
    payload_revision VARCHAR(255),
    payload_type VARCHAR(255) NOT NULL,
    processing_group VARCHAR(255) NOT NULL,
    sequence_identifier VARCHAR(255) NOT NULL,
    time_stamp VARCHAR(255) NOT NULL,
    token_type VARCHAR(255),
    type VARCHAR(255),
    diagnostics BLOB,
    meta_data BLOB,
    payload BLOB NOT NULL,
    token BLOB,
    PRIMARY KEY (dead_letter_id)
) ENGINE=InnoDB;
CREATE INDEX ix_axon_dead_letter_group ON dead_letter_entry (processing_group);
CREATE INDEX ix_axon_dead_letter_sequence ON dead_letter_entry (processing_group, sequence_identifier);
CREATE UNIQUE INDEX uk_axon_dead_letter_position ON dead_letter_entry (processing_group, sequence_identifier, sequence_index);
