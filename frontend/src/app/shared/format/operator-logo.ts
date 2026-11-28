/** The logo (in public/images/operators) of a company running BKM's buses; null for other operators. */
export function operatorLogo(operatingAgency: string | null | undefined): string | null {
  return operatingAgency && ['KPKM', 'KPK', 'KZK'].includes(operatingAgency)
    ? `/images/operators/${operatingAgency.toLowerCase()}.png`
    : null;
}
