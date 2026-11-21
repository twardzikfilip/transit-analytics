/**
 * Regularity grades for the coefficient of variation (CV) of headways — standard deviation of the gaps between
 * departures divided by their mean; 0 = perfectly even. Bands as in the Transit Capacity and Quality of Service
 * Manual (TCQSM) for headway regularity.
 */
export const REGULARITY_GRADES: { grade: string; maxCv: number; meaning: string }[] = [
  { grade: 'A', maxCv: 0.21, meaning: 'very even' },
  { grade: 'B', maxCv: 0.3, meaning: 'even' },
  { grade: 'C', maxCv: 0.39, meaning: 'mostly even' },
  { grade: 'D', maxCv: 0.52, meaning: 'uneven' },
  { grade: 'E', maxCv: 0.74, meaning: 'very uneven' },
  { grade: 'F', maxCv: Infinity, meaning: 'irregular' },
];

export function regularityGrade(cv: number | null): string | null {
  if (cv === null) return null;
  return REGULARITY_GRADES.find((g) => cv <= g.maxCv)!.grade;
}

export function regularityMeaning(cv: number | null): string {
  if (cv === null) return 'Too few departures to tell';
  const g = REGULARITY_GRADES.find((b) => cv <= b.maxCv)!;
  return `Grade ${g.grade}: ${g.meaning} (CV ${cv.toFixed(2)})`;
}
