/** Escapes text for HTML built as a string (map popups and tooltips, which Leaflet takes as HTML). */
export function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}
