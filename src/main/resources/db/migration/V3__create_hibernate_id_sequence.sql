-- Dedicated sequence for Hibernate batch-allocation.
-- The table id column was created with BIGSERIAL (implicit security_events_id_seq,
-- INCREMENT BY 1).  Hibernate's pooled-lo allocator requires the sequence
-- INCREMENT to match allocationSize (50).  Rather than altering the BIGSERIAL
-- sequence (which could disturb any direct SQL inserts), we create a separate
-- sequence used exclusively by Hibernate.  Hibernate always sets the id column
-- explicitly, so the BIGSERIAL default is never invoked for JPA-managed rows.
CREATE SEQUENCE security_events_hibernate_id_seq
    START WITH 1
    INCREMENT BY 50;
