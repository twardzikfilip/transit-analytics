/**
 * Delay generation: how much delay buses gain (or recover) between consecutive stops. The delay is the feed's, taken
 * just after a bus leaves each stop; the change between two stops is the delay generated there.
 */
import { StatsWindow } from './vehicle-stats';

/** A part of the day; the first is the whole day. */
export interface DayPart {
  key: string;
  label: string;
}

/** One direction between two consecutive stops, all lines together. Per band, aligned with the report's bands. */
export interface DelayStretch {
  from: string;
  to: string;
  fromLat: number;
  fromLon: number;
  toLat: number;
  toLon: number;
  lines: string[];
  runs: number[];
  /** Average change in delay per bus (positive: generated); null without runs. */
  meanSeconds: (number | null)[];
}

export interface DelayLineSummary {
  line: string;
  runs: number;
  /** Average change in delay from a run's first stop to its last. */
  meanGainSeconds: number;
  medianGainSeconds: number;
  /** Share of runs gaining 3 minutes or more. */
  gainingShare: number;
  /** Delay gained by all its runs together, per recorded day. */
  generatedMinutesPerDay: number;
  worstFrom: string | null;
  worstTo: string | null;
  worstMeanSeconds: number | null;
}

export interface DelayGenerationReport {
  window: StatsWindow;
  from: string;
  to: string;
  /** Service days with recordings; per-day figures are over these. */
  days: number;
  runs: number;
  bands: DayPart[];
  stretches: DelayStretch[];
  lines: DelayLineSummary[];
}

/** A stretch of one direction of a line. */
export interface DelayLineStretch {
  from: string;
  to: string;
  fromSeq: number;
  runs: number;
  meanSeconds: number;
  medianSeconds: number;
  p90Seconds: number;
  /** Share of runs gaining a minute or more here. */
  gainingShare: number;
  /** Average delay on leaving `from`. */
  delayBefore: number;
  /** Average change by hour of the day (0–23), null with under 3 runs. */
  byHour: (number | null)[];
  /** Runs that gained the most here. */
  exampleIds: number[];
}

export interface DelayDirection {
  headsign: string;
  runs: number;
  meanGainSeconds: number;
  /** In route order. */
  stretches: DelayLineStretch[];
  /** The runs that gained the most delay. */
  exampleIds: number[];
}

export interface DelayBrigadeGain {
  brigade: string;
  runs: number;
  meanGainSeconds: number;
  maxGainSeconds: number;
}

/** Leaving a stop: when, and the delay then (scheduled = left − delay). */
export interface DelayExampleStop {
  stop: string;
  seq: number;
  left: string;
  delaySeconds: number;
}

/** A run: the delay on leaving each stop. */
export interface DelayExample {
  id: number;
  day: string;
  vehicle: string;
  brigade: string | null;
  headsign: string | null;
  gainSeconds: number;
  stops: DelayExampleStop[];
}

export interface DelayLineReport {
  window: StatsWindow;
  from: string;
  to: string;
  line: string;
  days: number;
  directions: DelayDirection[];
  brigades: DelayBrigadeGain[];
  examples: DelayExample[];
}
