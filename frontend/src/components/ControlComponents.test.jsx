import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

const {
  mockStartScenario,
  mockStopScenario,
  mockSetAlgorithm,
  mockCreateBackend,
  mockGetScenarios,
  mockGetAlgorithms,
  mockGetBackends,
} = vi.hoisted(() => ({
  mockStartScenario: vi.fn(() => Promise.resolve({})),
  mockStopScenario: vi.fn(() => Promise.resolve({})),
  mockSetAlgorithm: vi.fn(() => Promise.resolve({})),
  mockCreateBackend: vi.fn(() => Promise.resolve({})),
  mockGetScenarios: vi.fn(() => Promise.resolve([
    { name: 'consumer-drop', description: 'Drop backends', active: false },
    { name: 'backend-recovery', description: 'Recover a backend', active: false },
  ])),
  mockGetAlgorithms: vi.fn(() => Promise.resolve(['round-robin', 'least-connections', 'random'])),
  mockGetBackends: vi.fn(() => Promise.resolve([])),
}))

vi.mock('../lib/api', () => ({
  api: {
    getScenarios: mockGetScenarios,
    startScenario: mockStartScenario,
    stopScenario: mockStopScenario,
    getAlgorithms: mockGetAlgorithms,
    setAlgorithm: mockSetAlgorithm,
    getBackends: mockGetBackends,
    createBackend: mockCreateBackend,
  },
}))

vi.mock('../hooks/useMetrics', () => ({
  useMetrics: () => ({ snapshot: null, events: [], connectionState: 'connected', error: null, reconnect: () => {} }),
}))

import { ScenarioPanel } from './ScenarioPanel'
import { AlgorithmSelector } from './AlgorithmSelector'
import { BackendConfigPanel } from './BackendConfigPanel'
import { Toast } from './Toast'

describe('ScenarioPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetScenarios.mockResolvedValue([
      { name: 'consumer-drop', description: 'Drop backends', active: false },
      { name: 'backend-recovery', description: 'Recover a backend', active: false },
    ])
  })

  it('clicking Start calls api.startScenario and shows active state', async () => {
    render(<ScenarioPanel />)
    await waitFor(() => expect(screen.getByText('consumer-drop')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /start consumer-drop/i }))
    await waitFor(() => expect(mockStartScenario).toHaveBeenCalledWith('consumer-drop', {}))
  })

  it('clicking Stop calls api.stopScenario', async () => {
    mockGetScenarios.mockResolvedValue([
      { name: 'consumer-drop', description: 'Drop backends', active: true },
    ])
    render(<ScenarioPanel />)
    await waitFor(() => expect(screen.getByText('consumer-drop')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /stop consumer-drop/i }))
    await waitFor(() => expect(mockStopScenario).toHaveBeenCalledWith('consumer-drop'))
  })
})

describe('AlgorithmSelector', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetAlgorithms.mockResolvedValue(['round-robin', 'least-connections', 'random'])
  })

  it('changing dropdown calls api.setAlgorithm', async () => {
    render(<AlgorithmSelector />)
    await waitFor(() => expect(screen.getByRole('combobox')).toBeInTheDocument())
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'least-connections' } })
    await waitFor(() => expect(mockSetAlgorithm).toHaveBeenCalledWith('least-connections'))
  })
})

describe('BackendConfigPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetBackends.mockResolvedValue([])
  })

  it('submit with negative latency shows inline error and does NOT call api.createBackend', async () => {
    render(<BackendConfigPanel />)
    await waitFor(() => expect(screen.getByLabelText(/name/i)).toBeInTheDocument())
    fireEvent.change(screen.getByLabelText(/name/i), { target: { value: 'test-backend' } })
    fireEvent.change(screen.getByLabelText(/latency/i), { target: { value: '-5' } })
    fireEvent.click(screen.getByRole('button', { name: /add backend/i }))
    await waitFor(() => expect(screen.getByText(/latency must be non-negative/i)).toBeInTheDocument())
    expect(mockCreateBackend).not.toHaveBeenCalled()
  })

  it('valid submit calls api.createBackend', async () => {
    render(<BackendConfigPanel />)
    await waitFor(() => expect(screen.getByLabelText(/name/i)).toBeInTheDocument())
    fireEvent.change(screen.getByLabelText(/name/i), { target: { value: 'test-backend' } })
    fireEvent.change(screen.getByLabelText(/latency/i), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: /add backend/i }))
    await waitFor(() => expect(mockCreateBackend).toHaveBeenCalled())
  })
})

describe('Toast', () => {
  it('renders a toast message when an error occurs', async () => {
    render(<Toast message="Something went wrong" onClose={() => {}} />)
    expect(screen.getByText('Something went wrong')).toBeInTheDocument()
  })
})
