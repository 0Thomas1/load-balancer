import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('./useMetrics', () => ({
  useMetrics: () => ({ snapshot: null, connected: false }),
}))

import App from './App'

describe('App', () => {
  it('renders the Load Balancer Sandbox header', () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: 'Load Balancer Sandbox' })).toBeInTheDocument()
  })

  it('renders the empty scaffold panels', () => {
    render(<App />)
    for (const name of ['Metrics', 'Backends', 'Scenarios', 'Traffic']) {
      expect(screen.getByRole('heading', { name })).toBeInTheDocument()
    }
    expect(screen.getByText('Disconnected')).toBeInTheDocument()
  })
})