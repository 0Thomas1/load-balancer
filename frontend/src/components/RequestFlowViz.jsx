import { useState, useEffect, useRef } from 'react'

const DOT_LIFETIME_MS = 800

export function RequestFlowViz({ events, backends }) {
  const [dots, setDots] = useState([])
  const nextId = useRef(0)

  useEffect(() => {
    if (!events || events.length === 0) return
    const latest = events[events.length - 1]
    if (!latest.backendId) return

    const id = nextId.current++
    setDots((prev) => [...prev, { id, backendId: latest.backendId, key: id }])

    const timer = setTimeout(() => {
      setDots((prev) => prev.filter((d) => d.id !== id))
    }, DOT_LIFETIME_MS)

    return () => clearTimeout(timer)
  }, [events])

  const backendNodes = backends || []

  return (
    <div className="relative h-48 w-full">
      <svg className="h-full w-full" viewBox="0 0 400 200">
        {/* Client node */}
        <circle cx="50" cy="100" r="20" fill="#38bdf8" />
        <text x="50" y="105" textAnchor="middle" fill="#0f172a" fontSize="10" fontWeight="bold">C</text>

        {/* LB node */}
        <circle cx="200" cy="100" r="25" fill="#818cf8" />
        <text x="200" y="105" textAnchor="middle" fill="#0f172a" fontSize="10" fontWeight="bold">LB</text>

        {/* Backend nodes */}
        {backendNodes.map((b, i) => {
          const y = backendNodes.length === 1 ? 100 : 40 + (i * 120) / (backendNodes.length - 1)
          return (
            <g key={b.id}>
              <circle cx="350" cy={y} r="18" fill={b.status === 'UP' ? '#34d399' : '#f87171'} />
              <text x="350" y={y + 4} textAnchor="middle" fill="#0f172a" fontSize="9" fontWeight="bold">
                {b.id}
              </text>
            </g>
          )
        })}

        {/* Connection lines */}
        <line x1="70" y1="100" x2="175" y2="100" stroke="#475569" strokeWidth="1" />
        {backendNodes.map((b, i) => {
          const y = backendNodes.length === 1 ? 100 : 40 + (i * 120) / (backendNodes.length - 1)
          return (
            <line key={b.id} x1="225" y1="100" x2="332" y2={y} stroke="#475569" strokeWidth="1" />
          )
        })}

        {/* Animated dots */}
        {dots.map((dot) => {
          const idx = backendNodes.findIndex((b) => b.id === dot.backendId)
          if (idx === -1) return null
          const targetY = backendNodes.length === 1 ? 100 : 40 + (idx * 120) / (backendNodes.length - 1)
          return (
            <circle
              key={dot.key}
              r="4"
              fill="#fbbf24"
              cx="70"
              cy="100"
            >
              <animate attributeName="cx" from="70" to="332" dur="0.6s" fill="freeze" />
              <animate attributeName="cy" from="100" to={targetY} dur="0.6s" fill="freeze" />
            </circle>
          )
        })}
      </svg>
    </div>
  )
}
