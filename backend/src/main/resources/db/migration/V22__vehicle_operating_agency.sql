-- Who runs each bus: KPKM, KPK or KZK for BKM's buses, else the bus's own agency. Filled by the application from
-- classification/operators.yml; null when not known.
ALTER TABLE vehicle ADD COLUMN operating_agency VARCHAR(64);
