import { createHash } from 'node:crypto'

const digest = value => createHash('sha256').update(JSON.stringify(value)).digest('hex')
const lookup = (value, path) => path.split('.').reduce((item, part) => item?.[part], value)
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b)

export function compareDeviceBaseline(baseline, observations = []) {
  if (!baseline) return { status: 'NOT_TESTED', baselineSha256: null, comparisonSha256: null, differences: [], uncovered: [] }
  const differences = [], uncovered = []
  const steps = baseline.scenario?.steps || []
  steps.forEach((step, index) => {
    const actual = observations[index]
    const expected = step.observation
    if (!actual) { uncovered.push({ step: index, reason: 'Action could not be replayed' }); return }
    if (actual.method !== expected.method || actual.path !== expected.path || actual.status !== expected.status) {
      differences.push({ step: index, field: 'request', observed: expected, simulated: actual }); return
    }
    if (!(step.comparisons || []).some(comparison => comparison.policy !== 'IGNORE')) {
      uncovered.push({ step: index, reason: 'No approved response comparison' })
    }
    for (const comparison of step.comparisons || []) {
      if (comparison.policy === 'IGNORE') continue
      const observed = comparison.expected ?? lookup(expected.responseSample, comparison.path)
      const simulated = lookup(actual.body, comparison.path)
      let matches = comparison.policy === 'TYPE' ? typeof observed === typeof simulated
        : comparison.policy === 'RANGE' ? typeof simulated === 'number' && simulated >= comparison.minimum && simulated <= comparison.maximum
          : equal(observed, simulated)
      if (!matches) differences.push({ step: index, field: comparison.path, observed, simulated })
    }
    if (step.checkpoint?.kind) {
      if (!actual.checkpoint) uncovered.push({ step: index, reason: 'Visible checkpoint was not replayed' })
      else if (!actual.checkpoint.passed) differences.push({ step: index, field: 'checkpoint', observed: step.checkpoint, simulated: actual.checkpoint })
    }
  })
  return { status: differences.length ? 'BLOCKED' : uncovered.length ? 'PARTIAL' : 'PASSED',
    baselineSha256: baseline.snapshotSha256, comparisonSha256: digest(steps.map(step => step.comparisons)), differences, uncovered }
}
