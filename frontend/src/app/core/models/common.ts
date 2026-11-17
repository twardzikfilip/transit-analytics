/** Shared API shapes: pages of results, the session and time windows. */

export type RoleName = 'ADMIN' | 'GUEST';

export interface PageMeta {
  size: number;
  /** Zero-based. */
  number: number;
  totalElements: number;
  totalPages: number;
}

export interface Page<T> {
  content: T[];
  page: PageMeta;
}

/** List filters sent as query parameters; null and empty values are left out. */
export type Filters = Record<string, string | number | null>;

export interface Session {
  token: string;
  username: string;
  role: RoleName;
}

/** A report's time window as the API takes it: ISO instants, `to` exclusive; no `from` for all time. */
export interface WindowQuery {
  from: string | null;
  to: string;
}
