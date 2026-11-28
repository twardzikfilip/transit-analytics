/** Route statistics from the weekday timetable. */

/** Per-line statistics from the weekday timetable (GET /api/routes/stats). */
export interface RouteStatsReport {
  /** Weekday service used, e.g. P_1349; null before a timetable is imported. */
  serviceId: string | null;
  peak: TimePeriod[];
  offPeak: TimePeriod[];
  /** Recorded delays are averaged over the weekdays of this many last days. */
  delayDays: number;
  routes: RouteStats[];
}

export interface TimePeriod {
  from: string;
  to: string;
}

export interface RouteStats {
  routeId: number;
  route: string;
  color: string | null;
  textColor: string | null;
  /** Main route, averaged over directions. */
  lengthKm: number | null;
  /** Average speed of main-route trips starting in the period (total distance / total time). */
  peakSpeedKmh: number | null;
  offPeakSpeedKmh: number | null;
  peakIntervalMinutes: number | null;
  offPeakIntervalMinutes: number | null;
  /** Coefficient of variation of the gaps between departures; 0 = perfectly even. */
  peakRegularityCv: number | null;
  offPeakRegularityCv: number | null;
  peakVehicles: number;
  trips: number;
  /** Recorded: mean delay in seconds of the route's buses in peak hours (negative: early); null without recordings. */
  peakDelaySeconds: number | null;
  offPeakDelaySeconds: number | null;
}
