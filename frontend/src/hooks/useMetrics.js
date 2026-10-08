import { useState, useEffect, useRef, useCallback } from 'react'
import { connectWebSocket } from '../lib/ws'

const MAX_EVENTS = 50

/**
 * Subscribe to the metrics WebSocket and expose the latest snapshot,
 * a bounded event deque, and connection state.
 *
 * @returns {{ snapshot: object | null, events: object[], connectionState: string, error: string | null, reconnect: () => void }}
 */
export function useMetrics() {
  const [snapshot, setSnapshot] = useState(null)
  const [events, setEvents] = useState([])
  const [connectionState, setConnectionState] = useState('disconnected')
  const [error, setError] = useState(null)
  const wsRef = useRef(null)

  useEffect(() => {
    const ws = connectWebSocket('ws://localhost:8080/ws/metrics', {
      onSnapshot: (snap) => setSnapshot(snap),
      onEvent: (evt) => setEvents((prev) => [...prev.slice(-(MAX_EVENTS - 1)), evt]),
      onStatus: (state) => setConnectionState(state),
    })
    wsRef.current = ws
    return () => ws.close()
  }, [])

  const reconnect = useCallback(() => {
    wsRef.current?.reconnect()
  }, [])

  return { snapshot, events, connectionState, error, reconnect }
}
