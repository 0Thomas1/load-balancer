import { describe, it, expect, vi } from 'vitest'
import { render, screen, act } from '@testing-library/react'

vi.mock('./hooks/useMetrics', () => ({
  useMetrics: () => ({ snapshot: null, events: [], connectionState: 'disconnected', error: null, reconnect: () => {} }),
}))

vi.mock('./lib/api', () => ({
  api: {
    getScenarios: vi.fn(() => Promise.resolve([])),
    startScenario: vi.fn(() => Promise.resolve({})),
    stopScenario: vi.fn(() => Promise.resolve({})),
    getAlgorithms: vi.fn(() => Promise.resolve(['round-robin'])),
    setAlgorithm: vi.fn(() => Promise.resolve({})),
    getBackends: vi.fn(() => Promise.resolve([])),
    createBackend: vi.fn(() => Promise.resolve({})),
    deleteBackend: vi.fn(() => Promise.resolve({})),
  },
}))

import App from './App'

describe('App', () => {
  it('renders the Load Balancer Sandbox header', async () => {
    render(<App />)
    await act(async () => {})
    expect(screen.getByRole('heading', { name: 'Load Balancer Sandbox' })).toBeInTheDocument()
  })

  it('renders the dashboard panels', async () => {
    render(<App />)
    await act(async () => {})
    for (const name of ['Metrics', 'Traffic', 'Backends', 'Request Flow']) {
      expect(screen.getByRole('heading', { name })).toBeInTheDocument()
    }
  })
})
