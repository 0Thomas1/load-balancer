export function Toast({ message, onClose }) {
  if (!message) return null
  return (
    <div className="fixed bottom-4 right-4 z-50 rounded border border-red-600 bg-red-900/90 px-4 py-3 text-sm text-red-100 shadow-lg">
      <div className="flex items-center gap-3">
        <span>{message}</span>
        <button onClick={onClose} className="text-red-300 hover:text-red-100">×</button>
      </div>
    </div>
  )
}
