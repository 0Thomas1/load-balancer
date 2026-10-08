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
    socket = new WebSocket(url)

    socket.onopen = () => {
      attempt = 0
      onStatus?.('connected')
    }

    socket.onmessage = (msg) => {
      try {
        const { type, payload } = JSON.parse(msg.data)
        if (type === 'snapshot') onSnapshot?.(payload)
        else if (type === 'event') onEvent?.(payload)
      } catch {
        // ignore malformed messages
      }
    }

    socket.onclose = () => {
      onStatus?.('reconnecting')
      if (!closedByUser) {
        const delay = Math.min(INITIAL_RECONNECT_DELAY * 2 ** attempt, MAX_RECONNECT_DELAY)
        attempt++
        timer = setTimeout(connect, delay)
      }
    }

    socket.onerror = () => {
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
