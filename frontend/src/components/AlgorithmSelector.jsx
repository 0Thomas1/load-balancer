import { useState, useEffect } from 'react'
import { api } from '../lib/api'

export function AlgorithmSelector({ onError }) {
  const [algorithms, setAlgorithms] = useState([])
  const [current, setCurrent] = useState('')

  useEffect(() => {
    api.getAlgorithms()
      .then((list) => {
        setAlgorithms(list)
        if (list.length > 0) setCurrent(list[0])
      })
      .catch((err) => onError?.(err.message))
  }, [onError])

  const handleChange = async (e) => {
    const name = e.target.value
    setCurrent(name)
    try {
      await api.setAlgorithm(name)
    } catch (err) {
      onError?.(err.message)
    }
  }

  return (
    <div className="flex items-center gap-2">
      <label htmlFor="algo" className="text-sm text-slate-400">Algorithm</label>
      <select
        id="algo"
        value={current}
        onChange={handleChange}
        className="rounded border border-slate-600 bg-slate-800 px-2 py-1 text-sm text-slate-100"
      >
        {algorithms.map((a) => (
          <option key={a} value={a}>{a}</option>
        ))}
      </select>
    </div>
  )
}