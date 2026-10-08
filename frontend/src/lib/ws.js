const INITIAL_RECONNECT_DELAY = 500
const MAX_RECONNECT_DELAY = 8000
const WATCHDOG_CHECK_MS = 1000
// The server pushes a snapshot at least twice a second, so a socket that claims
// to be open but has delivered nothing for 5s is a socket that died without a
// close event. Reconnect instead of waiting for the dashboard to be refreshed
// by hand.
const WATCHDOG_SILENCE_MS = 5000

/**
 * Connect to a WebSocket URL and dispatch typed messages.
 *
 * Auto-reconnects with exponential backoff (500ms * 2^attempts, capped at 8s).
 *
 * A browser sometimes is left holding a socket the server has silently stopped
 * serving (for example after a server-side broadcast failure removes the
 * session without a close frame). No `close` event ever fires, so the normal
 * reconnect never triggers. A watchdog therefore reconnects when an open socket
 * has been silent for {@link WATCHDOG_SILENCE_MS}: in the dashboard's world a
 * live socket always talks, so silence while "open" means it is dead.
 *
 * @param {string} url
 * @param {{ onSnapshot?: (snap: object) => void, onEvent?: (evt: object) => void, onStatus?: (state: string) => void }} handlers
 * @returns {{ reconnect: () => void, close: () => void }}
 */
export function connectWebSocket(url, { onSnapshot, onEvent, onStatus } = {}) {
  let socket = null
  let attempt = 0
  let timer = null
  let closedByUser = false
  let watchdogTimer = null
  let lastMessageAt = 0

  function armWatchdog() {
    clearWatchdog()
    watchdogTimer = setInterval(() => {
      // Only an OPEN socket that has stopped talking is suspicious; a socket
      // that is still connecting is covered by the normal close-based retry.
      if (closedByUser || !socket || socket.readyState !== 1 /* WebSocket.OPEN */) return
      if (lastMessageAt > 0 && Date.now() - lastMessageAt > WATCHDOG_SILENCE_MS) {
        onStatus?.('reconnecting')
        forceReconnect()
      }
    }, WATCHDOG_CHECK_MS)
  }

  function clearWatchdog() {
    if (watchdogTimer) {
      clearInterval(watchdogTimer)
      watchdogTimer = null
    }
  }

  function forceReconnect() {
    clearWatchdog()
    if (timer) clearTimeout(timer)
    attempt = 0
    if (socket) socket.close()
    connect()
  }

  function connect() {
    closedByUser = false
    const s = new WebSocket(url)
    socket = s
    lastMessageAt = 0

    s.onopen = () => {
      attempt = 0
      lastMessageAt = Date.now()
      armWatchdog()
      onStatus?.('connected')
    }

    s.onmessage = (msg) => {
      lastMessageAt = Date.now()
      try {
        const { type, payload } = JSON.parse(msg.data)
        if (type === 'snapshot') onSnapshot?.(payload)
        else if (type === 'event') onEvent?.(payload)
      } catch {
        // ignore malformed messages
      }
    }

    s.onclose = () => {
      clearWatchdog()
      // reconnect() closes the old socket and opens a fresh one immediately;
      // the old socket's onclose fires asynchronously AFTER the new socket
      // exists. Ignore its close so it cannot schedule a duplicate reconnect.
      if (closedByUser || s !== socket) return
      onStatus?.('reconnecting')
      const delay = Math.min(INITIAL_RECONNECT_DELAY * 2 ** attempt, MAX_RECONNECT_DELAY)
      attempt++
      timer = setTimeout(connect, delay)
    }

    s.onerror = () => {
      onStatus?.('error')
    }
  }

  connect()

  return {
    reconnect() {
      forceReconnect()
    },
    close() {
      closedByUser = true
      clearWatchdog()
      if (timer) clearTimeout(timer)
      if (socket) socket.close()
    },
  }
}