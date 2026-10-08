import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

const mockUseMetrics = vi.fn()
vi.mock('../hooks/useMetrics', () => ({
  useMetrics: () => mockUseMetrics(),
}))

vi.mock('./ScenarioPanel', () => ({ ScenarioPanel: () => <div>ScenarioPanel</div> }))
vi.mock('./AlgorithmSelector', () => ({ AlgorithmSelector: () => <div>AlgorithmSelector</div> }))
vi.mock('./BackendConfigPanel', () => ({ BackendConfigPanel: () => <div>BackendConfigPanel</div> }))

import Dashboard from './Dashboard'

const b1 = { id: 'b1', name: 'Backend-1', status: 'UP', weight: 1, activeConnections: 0, totalRequests: 10, errorCount: 0, avgLatency: 5.0, circuitState: 'CLOSED' }
const b2 = { id: 'b2', name: 'Backend-2', status: 'UP', weight: 1, activeConnections: 0, totalRequests: 20, errorCount: 1, avgLatency: 10.0, circuitState: 'CLOSED' }

describe('Dashboard', () => {
  it('renders backend names from the snapshot', () => {
    mockUseMetrics.mockReturnValue({
      snapshot: { timestamp: 1, totalRequests: 30, requestsPerSecond: 5, errorCount: 1, activeAlgorithm: 'round-robin', activeScenarios: [], backends: [b1, b2] },
      events: [],
      connectionState: 'connected',
      error: null,
      reconnect: () => {},
    })
    render(<Dashboard />)
    expect(screen.getByText('Backend-1')).toBeInTheDocument()
    expect(screen.getByText('Backend-2')).toBeInTheDocument()
  })

  it('shows a reconnecting badge when connectionState is reconnecting', () => {
    mockUseMetrics.mockReturnValue({
      snapshot: null,
      events: [],
      connectionState: 'reconnecting',
      error: null,
      reconnect: () => {},
    })
    render(<Dashboard />)
    expect(screen.getByText(/reconnecting/i)).toBeInTheDocument()
  })

  it('renders empty state when all backends are DOWN', () => {
    const down1 = { ...b1, status: 'DOWN' }
    const down2 = { ...b2, status: 'DOWN' }
    mockUseMetrics.mockReturnValue({
      snapshot: { timestamp: 1, totalRequests: 0, requestsPerSecond: 0, errorCount: 0, activeAlgorithm: 'round-robin', activeScenarios: [], backends: [down1, down2] },
      events: [],
      connectionState: 'connected',
      error: null,
      reconnect: () => {},
    })
    render(<Dashboard />)
    expect(screen.getByText(/no healthy backends/i)).toBeInTheDocument()
  })
})
