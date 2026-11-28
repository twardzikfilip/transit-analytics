/** A user of the app, as the Users page lists them (admins only). */
export interface AppUser {
  username: string;
  role: UserRole;
  createdAt: string;
}

export type UserRole = 'ADMIN' | 'GUEST';

/** A user to add; the backend checks the same rules as the dialog. */
export interface NewUser {
  username: string;
  password: string;
  role: UserRole;
}
