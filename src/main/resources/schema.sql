-- Axon 5 JDBC TokenSchema defaults, including the segment mask.
CREATE TABLE IF NOT EXISTS TokenEntry (
    processorName VARCHAR(255) NOT NULL,
    segment INTEGER NOT NULL,
    mask INTEGER NOT NULL,
    token BLOB NULL,
    tokenType VARCHAR(255) NULL,
    timestamp VARCHAR(255) NULL,
    owner VARCHAR(255) NULL,
    PRIMARY KEY (processorName, segment)
);
