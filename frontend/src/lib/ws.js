const INITIAL_RECONNECT_DELAY = 500
const MAX_RECONNECT_DELAY = 8000

/**
 * Connect to a WebSocket URL and dispatch typed messages.
 *
 * Auto-reconnects with exponential backoff (500ms * 2^attempts, capped at 8s).
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

  function connect() {
    closedByUser = false
    const s = new WebSocket(url)
    socket = s

    s.onopen = () => {
      attempt = 0
      onStatus?.('connected')
    }

    s.onmessage = (msg) => {
      try {
        const { type, payload } = JSON.parse(msg.data)
        if (type === 'snapshot') onSnapshot?.(payload)
        else if (type === 'event') onEvent?.(payload)
      } catch {
        // ignore malformed messages
      }
    }

    s.onclose = () => {
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
      if (timer) clearTimeout(timer)
      attempt = 0
      if (socket) socket.close()
      connect()
    },
    close() {
      closedByUser = true
      if (timer) clearTimeout(timer)
      if (socket) socket.close()
    },
  }
}
