import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  Brigade,
  DepotRun,
  LineDetail,
  LineVehicles,
  SegmentProfile,
} from '../../core/models/models';
import { BrigadeTimeline } from './brigade-timeline';
import { DepotRuns } from './depot-runs';
import { EndStops } from './end-stops';
import { LineVehiclesSection } from './line-vehicles';
import { SegmentChart } from './segment-chart';
import { VariantTiles } from './variant-tiles';

/** Renders a component with its inputs set and returns its element. */
function render<T>(component: Type<T>, inputs: Record<string, unknown>): HTMLElement {
  const fixture = TestBed.createComponent(component);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const cells = (row: Element | null) => [...(row?.querySelectorAll('td') ?? [])].map(text);

describe('Routes page parts', () => {
  it('summary tiles list every variant, mark depot runs and say when zone 2 is only on longer variants', () => {
    const variant = (kind: string, lastStop: string, km: number) => ({
      direction: 0,
      shapeId: kind,
      headsign: lastStop,
      kind,
      km,
      tripsPerDay: 5,
      shortestMin: 20,
      longestMin: 25,
      minSpeedKmh: 18,
      maxSpeedKmh: 22,
      firstStop: 'Centrum',
      lastStop,
      points: [],
      stops: [],
    });
    const detail = {
      base: {
        lengthKm: 9.9,
        shortestMin: 28,
        longestMin: 41,
        minSpeedKmh: 15.6,
        maxSpeedKmh: 19.7,
      },
      variants: [
        variant('main', 'Kleosin', 8.9),
        variant('longer', 'Koplany', 14.3),
        variant('depot', 'Wiejska', 4.5),
      ],
      zone2: [
        {
          direction: 0,
          headsign: 'Koplany',
          kind: 'longer',
          stops: 8,
          totalStops: 30,
          km: 5,
          totalKm: 14.3,
        },
      ],
      brigades: [{}, {}],
    } as unknown as LineDetail;

    const el = render(VariantTiles, { detail });

    expect(el.querySelectorAll('.tile')).toHaveLength(5);
    expect(text(el.querySelector('.tile strong'))).toBe('9.9 km');
    expect(el.querySelectorAll('.tile:first-child li')).toHaveLength(3);
    expect(text(el.querySelector('.tile:first-child li.depot'))).toContain('Wiejska');
    expect(text(el)).toContain('only on longer variants');
    expect(text(el)).toContain('15 trips a weekday');
  });

  it('lists depot runs with their brigade and direction, or says there are none', () => {
    const run: DepotRun = {
      brigade: '103-04',
      from: 7 * 3600 + 11 * 60,
      to: 7 * 3600 + 58 * 60,
      fromStop: 'Choroszcz/Cmentarz',
      toStop: 'Branickiego/Świętojańska',
      direction: 1,
      headsign: 'Piastowska',
      kind: 'pull_in',
      km: 19,
    };
    const el = render(DepotRuns, { depotRuns: [run] });
    expect(cells(el.querySelector('tbody tr'))).toEqual([
      '103-04',
      'To depot',
      '07:11–07:58',
      'Choroszcz/Cmentarz → Branickiego/Świętojańska',
      '19 km',
    ]);
    expect(text(render(DepotRuns, { depotRuns: [] }))).toContain('No depot runs in the timetable');
  });

  it('shows end stops with the stops grouped into them, and depot stops labelled', () => {
    const el = render(EndStops, {
      endStops: [
        { name: 'Centrum st. 4', alsoNames: ['Centrum st. 1'] },
        { name: 'Kleosin', alsoNames: [] },
      ],
      depotStops: [{ name: 'Wiejska/Politechnika', alsoNames: ['Wiejska/Pogodna'] }],
    });
    expect(text(el.querySelector('.stop-chip'))).toBe('Centrum st. 4 also Centrum st. 1');
    const depot = el.querySelector('.stop-chip.depot');
    expect(text(depot?.querySelector('.tag') ?? null)).toBe('depot');
    expect(text(depot)).toContain('Wiejska/Politechnika also Wiejska/Pogodna');
    expect(text(render(EndStops, { endStops: [], depotStops: [] }))).toContain('None');
  });

  it('lists the vehicles, the most used per brigade and changes during a day', () => {
    const vehicles: LineVehicles = {
      days: 7,
      vehicles: [
        {
          number: '032',
          model: 'Yutong U12',
          days: 3,
          brigades: ['103-01', '103-02'],
          lastSeen: '',
        },
      ],
      brigades: [{ brigade: '103-01', mostUsed: '032', mostUsedDays: 2, days: 6, vehicles: 9 }],
      switches: [
        {
          day: '2026-11-09',
          brigade: '103-01',
          spells: [
            { number: '224', from: '2026-11-09T06:37:05Z', to: '2026-11-09T16:19:52Z' },
            { number: '221', from: '2026-11-09T16:26:03Z', to: '2026-11-09T18:44:14Z' },
          ],
        },
      ],
    };
    const el = render(LineVehiclesSection, { vehicles });
    expect(cells(el.querySelector('tbody tr'))).toEqual([
      '032',
      'Yutong U12',
      '3',
      '103-01, 103-02',
    ]);
    expect(text(el.querySelector('.switches li'))).toBe(
      '103-01 · Mon 9 Nov: 224 until 17:19 → 221 from 17:26',
    );

    expect(
      text(render(LineVehiclesSection, { vehicles: { ...vehicles, switches: [] } })),
    ).toContain('Every brigade kept the same bus all day');
    expect(text(render(LineVehiclesSection, { vehicles: null, loading: true }))).toContain(
      'Loading vehicles',
    );
  });

  it('draws one bar per stretch and numbers the three slowest', () => {
    const profile: SegmentProfile = {
      direction: 0,
      headsign: 'Kleosin',
      runs: 12,
      segments: [30, 12, 40, 18, 25].map((kmh, i) => ({
        from: `S${i}`,
        to: `S${i + 1}`,
        fromKm: i,
        toKm: i + 1,
        kmh,
      })),
    };
    const el = render(SegmentChart, { profile });
    expect(el.querySelectorAll('rect.bar')).toHaveLength(5);
    expect(el.querySelectorAll('rect.bar.slow')).toHaveLength(3);
    expect([...el.querySelectorAll('.slowest li')].map(text)).toEqual([
      'S1 → S2 12 km/h',
      'S3 → S4 18 km/h',
      'S4 → S5 25 km/h',
    ]);
  });

  it('draws each brigade with its runs, depot runs and labelled breaks', () => {
    const at = (h: number, m = 0) => h * 3600 + m * 60;
    const brigade: Brigade = {
      name: '005-06',
      start: at(5, 41),
      end: at(17, 50),
      runs: [
        {
          from: at(5, 41),
          to: at(5, 48),
          direction: 1,
          headsign: 'Dziesięciny',
          kind: 'depot',
          depot: 'pull_out',
        },
        {
          from: at(6),
          to: at(6, 30),
          direction: 0,
          headsign: 'Sybiraków',
          kind: 'main',
          depot: null,
        },
        {
          from: at(13, 36),
          to: at(14, 6),
          direction: 1,
          headsign: 'Dziesięciny',
          kind: 'main',
          depot: null,
        },
      ],
      breaks: [{ from: at(9, 32), to: at(13, 36) }],
    };
    const el = render(BrigadeTimeline, { brigades: [brigade] });
    expect(text(el.querySelector('text.name'))).toBe('005-06');
    expect(el.querySelectorAll('rect.run')).toHaveLength(3);
    expect((el.querySelector('rect.run') as SVGElement).style.fill).toBe('var(--depot)');
    expect(text(el.querySelector('.break-label'))).toBe('4 h 04');
    expect([...el.querySelectorAll('text.time')].map(text)).toEqual(['05:41', '17:50']);
  });
});
