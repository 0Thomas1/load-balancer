/**
 * Stub hook for metrics data.
 *
 * Return shape (to be replaced by the real implementation in a later task):
 *   {
 *     snapshot: object | null,  // latest backend snapshot, null before first load
 *     connected: boolean,        // true once the WebSocket is open
 *   }
 *
 * The stub always returns an empty (disconnected) state so the scaffold renders
 * without a backend. `src/App.test.jsx` mocks this module with the same shape.
 *
 * @returns {{ snapshot: null, connected: false }}
 */
export function useMetrics() {
  return { snapshot: null, connected: false }
}