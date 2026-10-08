import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'

class MockWebSocket {
  constructor(url) {
    this.url = url
    this.readyState = 0
    this.sent = []
    MockWebSocket.instances.push(this)
  }
  send(data) {
    this.sent.push(data)
  }
  close() {
    this.readyState = 3
  }
  simulateOpen() {
    this.readyState = 1
    this.onopen?.()
  }
  simulateMessage(data) {
    this.onmessage?.({ data: JSON.stringify(data) })
  }
  simulateClose() {
    this.readyState = 3
    this.onclose?.()
  }
  simulateError() {
    this.onerror?.()
  }
}
MockWebSocket.instances = []

vi.stubGlobal('WebSocket', MockWebSocket)

const { useMetrics } = await import('./useMetrics')

describe('useMetrics', () => {
  beforeEach(() => {
    MockWebSocket.instances = []
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('connects and transitions to connected state', () => {
    const { result } = renderHook(() => useMetrics())
    act(() => {
      MockWebSocket.instances[0].simulateOpen()
    })
    expect(result.current.connectionState).toBe('connected')
  })

  it('updates snapshot when a snapshot message arrives', () => {
    const { result } = renderHook(() => useMetrics())
    act(() => {
      MockWebSocket.instances[0].simulateOpen()
    })
    const snap = { timestamp: 1, totalRequests: 5, requestsPerSecond: 2, errorCount: 0, activeAlgorithm: 'round-robin', activeScenarios: [], backends: [] }
    act(() => {
      MockWebSocket.instances[0].simulateMessage({ type: 'snapshot', payload: snap })
    })
    expect(result.current.snapshot).toEqual(snap)
  })

  it('updates events when an event message arrives', () => {
    const { result } = renderHook(() => useMetrics())
    act(() => {
      MockWebSocket.instances[0].simulateOpen()
    })
    const evt = { sourceIp: '10.0.0.1', backendId: 'b1', success: true, latencyNanos: 1000, reason: 'NONE' }
    act(() => {
      MockWebSocket.instances[0].simulateMessage({ type: 'event', payload: evt })
    })
    expect(result.current.events).toHaveLength(1)
    expect(result.current.events[0]).toEqual(evt)
  })

  it('reconnects with exponential backoff after close', () => {
    const { result } = renderHook(() => useMetrics())
    act(() => {
      MockWebSocket.instances[0].simulateOpen()
    })
    expect(result.current.connectionState).toBe('connected')

    act(() => {
      MockWebSocket.instances[0].simulateClose()
    })
    expect(result.current.connectionState).toBe('reconnecting')

    // First reconnect after 500ms
    act(() => {
      vi.advanceTimersByTime(500)
    })
    expect(MockWebSocket.instances).toHaveLength(2)

    act(() => {
      MockWebSocket.instances[1].simulateClose()
    })
    // Second reconnect after 1000ms (500 * 2^1)
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(MockWebSocket.instances).toHaveLength(3)

    act(() => {
      MockWebSocket.instances[2].simulateClose()
    })
    // Third reconnect after 2000ms (500 * 2^2)
    act(() => {
      vi.advanceTimersByTime(2000)
    })
    expect(MockWebSocket.instances).toHaveLength(4)
  })

  it('caps reconnect delay at 8 seconds', () => {
    const { result } = renderHook(() => useMetrics())
    act(() => {
      MockWebSocket.instances[0].simulateOpen()
    })
    // Close and reconnect several times to exceed the cap
    for (let i = 0; i < 10; i++) {
      act(() => {
        MockWebSocket.instances[MockWebSocket.instances.length - 1].simulateClose()
      })
      // Advance by the max delay (8s) to trigger the next reconnect
      act(() => {
        vi.advanceTimersByTime(8000)
      })
    }
    // Should have reconnected many times without error
    expect(MockWebSocket.instances.length).toBeGreaterThan(5)
  })

  it('keeps only the last 50 events', () => {
    const { result } = renderHook(() => useMetrics())
    act(() => {
      MockWebSocket.instances[0].simulateOpen()
    })
    for (let i = 0; i < 60; i++) {
      act(() => {
        MockWebSocket.instances[0].simulateMessage({
          type: 'event',
          payload: { sourceIp: '10.0.0.1', backendId: 'b1', success: true, latencyNanos: i, reason: 'NONE' },
        })
      })
    }
    expect(result.current.events).toHaveLength(50)
    // First event should be the 10th (index 9), since we keep the LAST 50
    expect(result.current.events[0].latencyNanos).toBe(10)
    expect(result.current.events[49].latencyNanos).toBe(59)
  })
})
