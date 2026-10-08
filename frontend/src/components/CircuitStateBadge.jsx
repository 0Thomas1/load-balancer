const COLORS = {
  CLOSED: 'bg-green-500',
  OPEN: 'bg-red-500',
  HALF_OPEN: 'bg-yellow-500',
}

export function CircuitStateBadge({ state }) {
  const color = COLORS[state] || 'bg-gray-500'
  return (
    <span className={`inline-block rounded px-2 py-0.5 text-xs font-medium text-white ${color}`}>
      {state}
    </span>
  )
}
