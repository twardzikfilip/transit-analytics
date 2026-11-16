import { resolveWindow, toLocalInput } from './time-window';

describe('position history time window', () => {
  const now = new Date(2026, 10, 16, 14, 2, 41);

  it('uses a preset ending at the next full minute', () => {
    const w = resolveWindow({ range: '6h', from: null, to: null }, now);
    expect(w.preset).toBe('6h');
    expect(toLocalInput(w.to)).toBe('2026-11-16T14:03');
    expect(toLocalInput(w.from)).toBe('2026-11-16T08:03');
  });

  it('falls back to 24 h for unknown or missing ranges', () => {
    expect(resolveWindow({ range: null, from: null, to: null }, now).preset).toBe('24h');
    expect(resolveWindow({ range: '5y', from: null, to: null }, now).preset).toBe('24h');
  });

  it('accepts a custom window, but not a reversed one', () => {
    const custom = resolveWindow(
      { range: '6h', from: '2026-11-16T05:00', to: '2026-11-16T17:00' },
      now,
    );
    expect(custom.preset).toBeNull();
    expect(toLocalInput(custom.from)).toBe('2026-11-16T05:00');
    expect(
      resolveWindow({ range: null, from: '2026-11-16T17:00', to: '2026-11-16T05:00' }, now).preset,
    ).toBe('24h');
  });
});
