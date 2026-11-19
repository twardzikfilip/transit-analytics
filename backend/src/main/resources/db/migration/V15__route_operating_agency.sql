-- The company operating each line for the organiser (BKM): KPKM, KPK or KZK. Filled by the application from
-- classification/operators.yml; null for other operators' lines.
ALTER TABLE route ADD COLUMN operating_agency VARCHAR(16);
