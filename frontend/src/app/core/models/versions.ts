/** A version and the day it was released ("2026-11-24"; null when unknown). */
export interface Release {
  version: string;
  released: string | null;
}

/** The versions running on the server. */
export interface Versions {
  backend: Release;
  /** transit-recorder's; null when it doesn't answer. */
  recorder: Release | null;
}
