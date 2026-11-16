/** Formatting of times, durations and delays, shared by the pages. */

/** Delay as "m:ss", with a minus sign when early; "—" when unknown. */
export function formatDelay(seconds: number | null): string {
  if (seconds === null) return '—';
  const sign = seconds < 0 ? '−' : '';
  const abs = Math.abs(Math.round(seconds));
  return `${sign}${Math.floor(abs / 60)}:${String(abs % 60).padStart(2, '0')}`;
}

/** Value for a datetime-local input, in local time ("2026-11-16T14:05"). */
export function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Clock time of seconds after midnight; times after midnight wrap (24:14 → "00:14"). */
export function clock(seconds: number): string {
  const minutes = Math.round(seconds / 60);
  const h = Math.floor(minutes / 60) % 24;
  return `${String(h).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}

/** A duration in minutes: "45 min", or "4 h 04" from an hour up. */
export function duration(minutes: number): string {
  const m = Math.round(minutes);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')}`;
}

const LOCAL_TIME = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  minute: '2-digit',
  timeZone: 'Europe/Warsaw',
});

/** Clock time in Białystok of an instant, e.g. "17:19". */
export function localTime(iso: string): string {
  return LOCAL_TIME.format(new Date(iso));
}

/** Round tick step (hours) so that at most `maxTicks` ticks fit. */
export function hourTickStep(span: number, maxTicks: number): number {
  return [1, 2, 3, 4, 6, 12].find((s) => span / s <= maxTicks) ?? 24;
}
