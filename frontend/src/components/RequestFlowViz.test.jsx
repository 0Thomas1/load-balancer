import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, act } from '@testing-library/react'
import { RequestFlowViz } from './RequestFlowViz'

describe('RequestFlowViz', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  const backends = (n) =>
    Array.from({ length: n }, (_, i) => ({ id: `b${i + 1}`, name: `Backend-${i + 1}`, status: 'UP' }))

  const dots = (container) => container.querySelectorAll('circle[fill="#fbbf24"]').length

  it('adds a dot per streamed event', () => {
    const { container, rerender } = render(
      <RequestFlowViz events={[{ backendId: 'b1' }]} backends={backends(1)} />
    )
    expect(dots(container)).toBe(1)

    rerender(
      <RequestFlowViz
        events={[{ backendId: 'b1' }, { backendId: 'b2' }]}
        backends={backends(2)}
      />
    )
    expect(dots(container)).toBe(2)
  })

  it('expires dots even while new events keep streaming', () => {
    const { container, rerender } = render(
      <RequestFlowViz events={[{ backendId: 'b1' }]} backends={backends(1)} />
    )

    // Stream a new event every 100 ms — far faster than the 800 ms dot
    // lifetime. The original implementation cancelled each dot's removal
    // timer in the effect cleanup, so dots accumulated forever; this keeps
    // the earliest dots expiring on schedule.
    for (let i = 0; i < 12; i++) {
      act(() => {
        vi.advanceTimersByTime(100)
        rerender(<RequestFlowViz events={[{ backendId: 'b1' }]} backends={backends(1)} />)
      })
    }
    // 13 events streamed by now (t=0..1200); the t=0..300 dots have already
    // expired, so far fewer than 13 dots remain.
    expect(dots(container)).toBeLessThan(10)

    // Stop the stream; remaining dots still expire on schedule.
    act(() => {
      vi.advanceTimersByTime(1600)
    })
    expect(dots(container)).toBe(0)
  })
})