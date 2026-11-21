import { TestBed } from '@angular/core/testing';
import { Theme, savedTheme } from './theme';

describe('Theme', () => {
  afterEach(() => localStorage.removeItem('theme'));

  it('is light unless dark was saved', () => {
    expect(savedTheme(null)).toBe('light');
    expect(savedTheme({ getItem: () => 'dark' })).toBe('dark');
    expect(savedTheme({ getItem: () => 'purple' })).toBe('light');
    expect(
      savedTheme({
        getItem: () => {
          throw new Error('blocked');
        },
      }),
    ).toBe('light');
  });

  it('applies the theme to the root element and remembers it', () => {
    const theme = TestBed.inject(Theme);
    TestBed.tick();
    expect(document.documentElement.dataset['theme']).toBe('light');
    theme.set('dark');
    TestBed.tick();
    expect(document.documentElement.dataset['theme']).toBe('dark');
    expect(localStorage.getItem('theme')).toBe('dark');
  });
});
