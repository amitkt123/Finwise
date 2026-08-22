-- Tax engine extension: interest-income, insurance, and non-equity-flat regimes
-- need these fields on investments (interest rate for FD/post-office schemes,
-- maturity date for informational display, sum assured / annual premium for the
-- Section 10(10D) insurance exemption test). All nullable — existing rows are
-- unaffected.
ALTER TABLE public.investments ADD COLUMN interest_rate numeric(8,4);
ALTER TABLE public.investments ADD COLUMN maturity_date date;
ALTER TABLE public.investments ADD COLUMN sum_assured numeric(19,4);
ALTER TABLE public.investments ADD COLUMN annual_premium numeric(19,4);
