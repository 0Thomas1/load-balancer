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
})