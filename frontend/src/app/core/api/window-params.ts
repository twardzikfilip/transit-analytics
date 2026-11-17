import { WindowQuery } from '../models/models';

/** Query parameters of a time window; `from` left out for all time (the backend starts at the first recording). */
export function windowParams(q: WindowQuery): Record<string, string> {
  return q.from ? { from: q.from, to: q.to } : { to: q.to };
}
