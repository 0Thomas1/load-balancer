import { useState, useEffect } from 'react'
import { api } from '../lib/api'

export function ScenarioPanel({ onError }) {
  const [scenarios, setScenarios] = useState([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.getScenarios()
      .then(setScenarios)
      .catch((err) => onError?.(err.message))
      .finally(() => setLoading(false))
  }, [onError])

  const refresh = () => {
    api.getScenarios().then(setScenarios).catch((err) => onError?.(err.message))
  }

  const handleStart = async (name) => {
    try {
      await api.startScenario(name, {})
      refresh()
    } catch (err) {
      onError?.(err.message)
    }
  }

  const handleStop = async (name) => {
    try {
      await api.stopScenario(name)
      refresh()
    } catch (err) {
      onError?.(err.message)
    }
  }

  const handleStopAll = async () => {
    for (const s of scenarios.filter((s) => s.active)) {
      try {
        await api.stopScenario(s.name)
      } catch (err) {
        onError?.(err.message)
      }
    }
    refresh()
  }

  if (loading) return <p className="text-sm text-slate-500">Loading scenarios…</p>

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-300">Scenarios</h3>
        <button
          type="button"
          onClick={handleStopAll}
          className="rounded bg-slate-700 px-2 py-1 text-xs text-slate-200 hover:bg-slate-600"
        >
          Stop All
        </button>
      </div>
      {scenarios.map((s) => (
        <div key={s.name} className="flex items-center justify-between rounded border border-slate-700 px-3 py-2">
          <div>
            <div className="text-sm font-medium">{s.name}</div>
            <div className="text-xs text-slate-400">{s.description}</div>
          </div>
          {s.active ? (
            <button
              type="button"
              onClick={() => handleStop(s.name)}
              aria-label={`Stop ${s.name}`}
              className="rounded bg-red-600 px-2 py-1 text-xs text-white hover:bg-red-500"
            >
              Stop
            </button>
          ) : (
            <button
              type="button"
              onClick={() => handleStart(s.name)}
              aria-label={`Start ${s.name}`}
              className="rounded bg-green-600 px-2 py-1 text-xs text-white hover:bg-green-500"
            >
              Start
            </button>
          )}
        </div>
      ))}
    </div>
  )
}