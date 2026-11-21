import { regularityGrade, regularityMeaning } from './regularity';

describe('regularity grades', () => {
  it('uses the TCQSM bands, boundaries included in the better grade', () => {
    expect(regularityGrade(0)).toBe('A');
    expect(regularityGrade(0.21)).toBe('A');
    expect(regularityGrade(0.22)).toBe('B');
    expect(regularityGrade(0.3)).toBe('B');
    expect(regularityGrade(0.39)).toBe('C');
    expect(regularityGrade(0.52)).toBe('D');
    expect(regularityGrade(0.74)).toBe('E');
    expect(regularityGrade(0.75)).toBe('F');
    expect(regularityGrade(null)).toBeNull();
  });

  it('explains a value', () => {
    expect(regularityMeaning(0.48)).toBe('Grade D: uneven (CV 0.48)');
    expect(regularityMeaning(null)).toBe('Too few departures to tell');
  });
});
