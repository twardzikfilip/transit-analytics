import { LatLon } from './geo';

/** The railways in and around Białystok and the Polregio and PKP Intercity trains on them. */
export interface CityRailReport {
  railways: Railway[];
  /** The days departures are counted on. */
  days: RailDay[];
  /** Empty while the timetable isn't loaded. */
  routes: RailRoute[];
}

export interface Railway {
  /** The railway line's number; null when it has none. */
  ref: string | null;
  /** A spur, e.g. to a depot or factory. */
  spur: boolean;
  points: LatLon[];
}

export interface RailDay {
  kind: 'weekday' | 'saturday' | 'sunday';
  /** yyyy-MM-dd. */
  date: string;
}

/** The railway from Białystok to one end of the network, with all trains on it, wherever they run beyond it. */
export interface RailRoute {
  /** The station it ends at. */
  id: string;
  /** From Białystok outwards. */
  stations: RailStation[];
  points: LatLon[];
  polregio: RailTrains;
  /** Polregio's and PKP Intercity's. */
  all: RailTrains;
}

export interface RailTrains {
  /** Where they start or end, the most trains first. */
  destinations: string[];
  /** Trains per day of the report's days, from and to Białystok. */
  departures: { fromCity: number; toCity: number }[];
}

export interface RailStation {
  name: string;
  lat: number;
  lon: number;
}
