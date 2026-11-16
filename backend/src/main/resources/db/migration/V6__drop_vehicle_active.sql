-- Nothing used the active flag; vehicles are either in the table or deleted.
ALTER TABLE vehicle DROP COLUMN active;
