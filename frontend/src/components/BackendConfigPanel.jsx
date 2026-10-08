import { useState, useEffect } from 'react'
import { api } from '../lib/api'

export function BackendConfigPanel({ onError }) {
  const [backends, setBackends] = useState([])
  const [name, setName] = useState('')
  const [latency, setLatency] = useState('')
  const [editingId, setEditingId] = useState(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api.getBackends().then(setBackends).catch((err) => onError?.(err.message))
  }, [onError])

  const resetForm = () => {
    setName('')
    setLatency('')
    setEditingId(null)
  }

  const startEdit = (b) => {
    setEditingId(b.id)
    setName(b.name)
    setLatency(b.avgLatency != null ? String(b.avgLatency) : '')
    setError('')
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')

    const lat = Number(latency)
    if (latency !== '' && (isNaN(lat) || lat < 0)) {
      setError('Latency must be non-negative')
      return
    }

    const data = { name }
    if (latency !== '') data.latencyMs = lat

    try {
      if (editingId) await api.updateBackendConfig(editingId, data)
      else await api.createBackend(data)
      resetForm()
      setBackends(await api.getBackends())
    } catch (err) {
      onError?.(err.message)
    }
  }

  const handleDelete = async (id) => {
    try {
      await api.deleteBackend(id)
      if (editingId === id) resetForm()
      setBackends(await api.getBackends())
    } catch (err) {
      onError?.(err.message)
    }
  }

  return (
    <div className="space-y-4">
      <form onSubmit={handleSubmit} className="space-y-2">
        <div className="flex gap-2">
          <label className="sr-only" htmlFor="backend-name">Name</label>
          <input
            id="backend-name"
            type="text"
            placeholder="Name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="rounded border border-slate-600 bg-slate-800 px-2 py-1 text-sm text-slate-100"
            required
          />
          <label className="sr-only" htmlFor="backend-latency">Latency (ms)</label>
          <input
            id="backend-latency"
            type="number"
            placeholder="Latency (ms)"
            value={latency}
            onChange={(e) => setLatency(e.target.value)}
            className="rounded border border-slate-600 bg-slate-800 px-2 py-1 text-sm text-slate-100"
          />
          <button type="submit" className="rounded bg-blue-600 px-3 py-1 text-sm text-white hover:bg-blue-500">
            {editingId ? 'Update Backend' : 'Add Backend'}
          </button>
          {editingId && (
            <button
              type="button"
              onClick={resetForm}
              className="rounded bg-slate-700 px-3 py-1 text-sm text-slate-200 hover:bg-slate-600"
            >
              Cancel
            </button>
          )}
        </div>
        {error && <p className="text-xs text-red-400">{error}</p>}
      </form>

      <div className="space-y-2">
        {backends.map((b) => (
          <div key={b.id} className="flex items-center justify-between rounded border border-slate-700 px-3 py-2 text-sm">
            <span>{b.name} <span className="text-slate-400">({b.id})</span></span>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => startEdit(b)}
                className="rounded bg-slate-600 px-2 py-0.5 text-xs text-white hover:bg-slate-500"
              >
                Edit
              </button>
              <button
                type="button"
                onClick={() => handleDelete(b.id)}
                className="rounded bg-red-600 px-2 py-0.5 text-xs text-white hover:bg-red-500"
              >
                Remove
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}