-- Store the displayable road/lot address in addition to route coordinates.
ALTER TABLE user_addresses
    ADD COLUMN address VARCHAR(255) NULL AFTER name;
