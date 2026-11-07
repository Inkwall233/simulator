const databaseName = 'skilllab-runtime-state'

function openDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(databaseName, 1)
    request.onupgradeneeded = () => request.result.createObjectStore('runs')
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

export async function withRunState(runId, initialState, evaluate) {
  if (typeof runId !== 'string' || !/^[a-zA-Z0-9-]{1,64}$/.test(runId)) throw new Error('Invalid run id')
  const db = await openDatabase()
  try {
    return await new Promise((resolve, reject) => {
      const transaction = db.transaction('runs', 'readwrite')
      const store = transaction.objectStore('runs')
      const read = store.get(runId)
      let result
      transaction.oncomplete = () => resolve(result)
      transaction.onabort = () => reject(transaction.error || new Error('State transaction aborted'))
      transaction.onerror = () => reject(transaction.error || new Error('State transaction failed'))
      read.onsuccess = () => {
        try {
          const previous = read.result || { state: structuredClone(initialState), revision: 0 }
          const evaluated = evaluate(structuredClone(previous.state))
          if (evaluated && typeof evaluated.then === 'function') throw new Error('State evaluator must be synchronous')
          if (!evaluated || !Object.hasOwn(evaluated, 'nextState') || !Object.hasOwn(evaluated, 'response')) {
            throw new Error('Invalid state evaluation')
          }
          const revision = previous.revision + 1
          store.put({ state: structuredClone(evaluated.nextState), revision }, runId)
          result = { ...evaluated, revision }
        } catch (error) {
          transaction.abort()
          reject(error)
        }
      }
    })
  } finally {
    db.close()
  }
}
