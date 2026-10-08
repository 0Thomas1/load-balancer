import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

const {
  mockStartScenario,
  mockStopScenario,
  mockSetAlgorithm,
  mockCreateBackend,
  mockUpdateBackendConfig,
  mockDeleteBackend,
  mockGetScenarios,
  mockGetAlgorithms,
  mockGetBackends,
} = vi.hoisted(() => ({
  mockStartScenario: vi.fn(() => Promise.resolve({})),
  mockStopScenario: vi.fn(() => Promise.resolve({})),
  mockSetAlgorithm: vi.fn(() => Promise.resolve({})),
  mockCreateBackend: vi.fn(() => Promise.resolve({})),
  mockUpdateBackendConfig: vi.fn(() => Promise.resolve({})),
  mockDeleteBackend: vi.fn(() => Promise.resolve({})),
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
    updateBackendConfig: mockUpdateBackendConfig,
    deleteBackend: mockDeleteBackend,
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
    mockGetScenarios
      .mockResolvedValueOnce([
        { name: 'consumer-drop', description: 'Drop backends', active: false },
        { name: 'backend-recovery', description: 'Recover a backend', active: false },
      ])
      .mockResolvedValue([
        { name: 'consumer-drop', description: 'Drop backends', active: true },
        { name: 'backend-recovery', description: 'Recover a backend', active: false },
      ])
    render(<ScenarioPanel />)
    await waitFor(() => expect(screen.getByRole('button', { name: /start consumer-drop/i })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /start consumer-drop/i }))
    await waitFor(() => expect(mockStartScenario).toHaveBeenCalledWith('consumer-drop', {}))
    await waitFor(() => expect(screen.getByRole('button', { name: /stop consumer-drop/i })).toBeInTheDocument())
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

  it('surfaces an api rejection to onError', async () => {
    mockStartScenario.mockRejectedValueOnce(new Error('scenario failed'))
    const onError = vi.fn()
    render(<ScenarioPanel onError={onError} />)
    await waitFor(() => expect(screen.getByRole('button', { name: /start consumer-drop/i })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /start consumer-drop/i }))
    await waitFor(() => expect(onError).toHaveBeenCalledWith('scenario failed'))
  })

  it('disables Start for backend-recovery while no backend is DOWN', async () => {
    render(<ScenarioPanel downCount={0} />)
    await waitFor(() => expect(screen.getByRole('button', { name: /start backend-recovery/i })).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /start backend-recovery/i })).toBeDisabled()
  })

  it('enables Start for backend-recovery once a backend is DOWN', async () => {
    render(<ScenarioPanel downCount={1} />)
    await waitFor(() => expect(screen.getByRole('button', { name: /start backend-recovery/i })).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /start backend-recovery/i })).toBeEnabled()
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

  it('surfaces an api rejection to onError', async () => {
    mockSetAlgorithm.mockRejectedValueOnce(new Error('algo failed'))
    const onError = vi.fn()
    render(<AlgorithmSelector onError={onError} />)
    await waitFor(() => expect(screen.getByRole('combobox')).toBeInTheDocument())
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'least-connections' } })
    await waitFor(() => expect(onError).toHaveBeenCalledWith('algo failed'))
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
    expect(mockUpdateBackendConfig).not.toHaveBeenCalled()
  })

  it('valid submit calls api.createBackend', async () => {
    render(<BackendConfigPanel />)
    await waitFor(() => expect(screen.getByLabelText(/name/i)).toBeInTheDocument())
    fireEvent.change(screen.getByLabelText(/name/i), { target: { value: 'test-backend' } })
    fireEvent.change(screen.getByLabelText(/latency/i), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: /add backend/i }))
    await waitFor(() => expect(mockCreateBackend).toHaveBeenCalledWith({ name: 'test-backend', latencyMs: 10 }))
  })

  it('editing a backend calls api.updateBackendConfig', async () => {
    mockGetBackends.mockResolvedValue([{ id: 'b1', name: 'Backend-1', avgLatency: 5 }])
    render(<BackendConfigPanel />)
    await waitFor(() => expect(screen.getByText('Backend-1')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /edit/i }))
    await waitFor(() => expect(screen.getByRole('button', { name: /update backend/i })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /update backend/i }))
    await waitFor(() => expect(mockUpdateBackendConfig).toHaveBeenCalledWith('b1', { name: 'Backend-1', latencyMs: 5 }))
    expect(mockCreateBackend).not.toHaveBeenCalled()
  })

  it('surfaces an api rejection to onError', async () => {
    mockCreateBackend.mockRejectedValueOnce(new Error('create failed'))
    const onError = vi.fn()
    render(<BackendConfigPanel onError={onError} />)
    await waitFor(() => expect(screen.getByLabelText(/name/i)).toBeInTheDocument())
    fireEvent.change(screen.getByLabelText(/name/i), { target: { value: 'test-backend' } })
    fireEvent.click(screen.getByRole('button', { name: /add backend/i }))
    await waitFor(() => expect(onError).toHaveBeenCalledWith('create failed'))
  })
})

describe('Toast', () => {
  it('renders a toast message when an error occurs', async () => {
    render(<Toast message="Something went wrong" onClose={() => {}} />)
    expect(screen.getByText('Something went wrong')).toBeInTheDocument()
  })

  it('dismiss button has an accessible label and calls onClose', async () => {
    const onClose = vi.fn()
    render(<Toast message="Something went wrong" onClose={onClose} />)
    const dismiss = screen.getByRole('button', { name: /dismiss notification/i })
    fireEvent.click(dismiss)
    expect(onClose).toHaveBeenCalled()
  })
})