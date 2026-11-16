/** Collapses numeric/uuid path segments so routes stay low-cardinality (e.g. /vehicles/:id). */
export function routeOf(path: string): string {
  return path.replace(/\/(\d+|[0-9a-f-]{36})(?=\/|$)/gi, '/:id') || '/';
}
