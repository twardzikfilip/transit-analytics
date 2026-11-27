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

/**
 * Train against bus between the railway stations in the city: the fastest Polregio journey (leaving at any time) and
 * the fastest bus journey leaving off-peak, between the bus stops nearest the stations, each with up to
 * `maxChanges` changes.
 */
export interface RailBusComparison {
  /** The weekday compared, yyyy-MM-dd; null while the trains' timetable isn't loaded. */
  day: string | null;
  /** When the buses leave, "HH:MM-HH:MM". */
  offPeak: string[];
  maxChanges: number;
  /** The stations in the city, with the bus stops nearest each. */
  stations: ComparedStation[];
  /** Every station to every other. */
  pairs: RailBusPair[];
}

export interface ComparedStation {
  name: string;
  /** Straight from the station. */
  stops: { name: string; meters: number }[];
}

export interface RailBusPair {
  from: string;
  to: string;
  /** Null when there is none. */
  train: RailBusJourney | null;
  bus: RailBusJourney | null;
}

export interface RailBusJourney {
  /** From the first departure to the last arrival, changes included. */
  minutes: number;
  /** "HH:MM". */
  departure: string;
  arrival: string;
  /** One per ride; a change between two. */
  legs: RailBusLeg[];
}

export interface RailBusLeg {
  /** The bus line, or the train's number. */
  line: string;
  from: string;
  to: string;
  departure: string;
  arrival: string;
}

/**
 * City Rail proposal no. 1: city trains on the five railways from Białystok, shortened to Klepacze, Fasty, Lewickie,
 * Wasilków and Kuriany, with new stations, and two ways of running them, each timetabled around today's trains.
 */
export interface CityRailProposal {
  /** The weekday whose trains the timetables keep clear of, yyyy-MM-dd; null while the timetable isn't loaded. */
  day: string | null;
  rules: ProposalRules;
  /** The railways, from Białystok outwards. */
  corridors: ProposalCorridor[];
  /** Empty while the timetable isn't loaded. */
  solutions: ProposalSolution[];
}

export interface ProposalRules {
  /** At least this long from any other train going the same way on the same railway. */
  headwayMinutes: number;
  /** At least this long at an end before going back. */
  turnaroundMinutes: number;
  /** At most this long between two trains of a line. */
  intervalMinutes: number;
  /** "HH:MM". */
  firstDeparture: string;
  lastDeparture: string;
  /** Seconds standing at a new station. */
  newStationDwell: number;
  /** Seconds standing at Białystok when running through. */
  hubDwell: number;
  /** Today's trains on the railways. */
  trainsConsidered: number;
}

export type ProposalStationKind = 'served' | 'unserved' | 'new';

export interface ProposalCorridor {
  /** Its end, in lower case ("klepacze"). */
  id: string;
  /** The railway line's number. */
  ref: string;
  /** Whether any train runs on it today. */
  served: boolean;
  stations: ProposalStation[];
  points: LatLon[];
}

export interface ProposalStation {
  name: string;
  kind: ProposalStationKind;
  lat: number;
  lon: number;
  /** Along the railway from Białystok. */
  km: number;
}

export interface ProposalSolution {
  id: 'radial' | 'through';
  lines: ProposalLine[];
}

export interface ProposalLine {
  /** "S1". */
  id: string;
  stations: { name: string; kind: ProposalStationKind; km: number }[];
  points: LatLon[];
  km: number;
  spacingKm: number;
  runMinutes: number;
  runMinutesBack: number;
  trainsPerDay: number;
  maxIntervalMinutes: number;
  vehicles: number;
  minTurnaround: number;
  /** The nearest any of its trains comes to another on the same railway and way; null when none. */
  closestMinutes: number | null;
  /** Its trains coming closer than the headway. */
  conflicts: number;
  out: ProposalTrip[];
  back: ProposalTrip[];
}

export interface ProposalTrip {
  /** "HH:MM" per station: the departure, at the last the arrival. */
  times: string[];
  conflict: boolean;
}
