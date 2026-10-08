import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('./hooks/useMetrics', () => ({
  useMetrics: () => ({ snapshot: null, events: [], connectionState: 'disconnected', error: null, reconnect: () => {} }),
}))

import App from './App'

describe('App', () => {
  it('renders the Load Balancer Sandbox header', () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: 'Load Balancer Sandbox' })).toBeInTheDocument()
  })

  it('renders the dashboard panels', () => {
    render(<App />)
    for (const name of ['Metrics', 'Traffic', 'Backends', 'Request Flow']) {
      expect(screen.getByRole('heading', { name })).toBeInTheDocument()
    }
  })
})
