/**
 * Colours used in code (map layers can't use CSS custom properties). Theme colours are read from the tokens in
 * styles.scss with cssColor(), so they follow light and dark mode; the data scales below are fixed.
 */

/** A colour token from styles.scss (e.g. '--depot'), or the fallback before styles load or outside a browser. */
export function cssColor(token: string, fallback: string): string {
  if (typeof document === 'undefined') return fallback;
  return getComputedStyle(document.documentElement).getPropertyValue(token).trim() || fallback;
}

/** Delay scale (Delays map): on time (≤ 1 min late, or early) green → 3 min yellow → 6 min orange → 10+ min red. */
export const DELAY_STOPS: { seconds: number; color: string; label: string }[] = [
  { seconds: 60, color: '#2e9e5b', label: 'on time' },
  { seconds: 180, color: '#f2c12e', label: '3 min' },
  { seconds: 360, color: '#f07f22', label: '6 min' },
  { seconds: 600, color: '#d7301f', label: '10+ min' },
];

/** A vehicle without a known delay. */
export const NO_DELAY_COLOR = '#8a8f98';

/**
 * Sequential blue, light → dark (reference palette steps 250–700; the lightest still stands out on a grey map),
 * for magnitudes such as buses per stretch.
 */
export const SEQUENTIAL_BLUE = ['#86b6ef', '#5598e7', '#2a78d6', '#1c5cab', '#104281', '#0d366b'];
