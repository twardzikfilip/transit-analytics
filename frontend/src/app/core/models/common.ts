/** Shared API shapes: pages of results, users and the session. */

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

export interface User {
  id: number;
  username: string;
  role: string;
  createdAt: string;
}

export interface UserRole {
  id: number;
  name: string;
}

/** Request payload type: entity without its id (plus write-only fields where needed). */
export type Payload<T> = Omit<T, 'id'>;

export type UserPayload = { username: string; password?: string; role: string };

export interface Session {
  token: string;
  username: string;
  role: RoleName;
}
