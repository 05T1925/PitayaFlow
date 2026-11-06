import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { AttemptHost } from './attempt-host.mjs'

const planned = 3
const dshSource = process.env.PF_DSH_SOURCE
let setupError = null
try {
  assert.ok(dshSource, 'PF_DSH_SOURCE required')
  assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], { cwd: dshSource, encoding: 'utf8' }).trim(),
    'ce7073d51254c26cd63b22a60835b8f948deb447')
} catch { setupError = 'fixed_source_unavailable' }

const observations = []
if (!setupError) for (let attempt = 1; attempt <= planned; attempt++) {
  const observation = { attempt, status: 'failed', failure_stage: null, start_ms: null, process_ms: null,
    release_ms: null, host_rss_before_bytes: process.memoryUsage().rss, host_rss_after_bytes: null,
    worker_pid_observed: false, result_usable: false, release_confirmed: false, home_removed: false }
  let host = null
  let stage = 'construct'
  let startedDone
  const startedAt = performance.now()
  try {
    host = new AttemptHost({ dshSource, ownerId: 'synthetic-owner-a', caseId: 'PF-C001-synthetic', fixtureId: 'a',
      credentialMarker: 'synthetic-measurement', deadlineMs: 30000 })
    stage = 'start'
    const start = await host.start()
    startedDone = performance.now()
    observation.start_ms = Math.round(startedDone - startedAt)
    assert.equal(start.type, 'started')
    stage = 'process'
    const result = await host.run(30000)
    observation.process_ms = Math.round(performance.now() - startedDone)
    assert.equal(result.type, 'result')
    assert.equal(result.delivery_usable, true)
    assert.deepEqual(result.registered_tools, ['read_synthetic_transcript'])
    observation.result_usable = true
    if (process.env.PF_MEASURE_FAIL_AT === `${attempt}:process`) throw new Error('injected measurement failure')
    observation.status = 'succeeded'
  } catch (error) {
    observation.failure_stage = stage
    observation.failure_class = error?.name ?? 'Error'
  } finally {
    if (host) {
      const releaseStarted = performance.now()
      try {
        const release = await host.close()
        observation.release_ms = Math.round(performance.now() - releaseStarted)
        observation.release_confirmed = release.tree_stop_confirmed === true
        observation.home_removed = release.home_removed === true
        if (!observation.release_confirmed || !observation.home_removed) {
          observation.failure_stage ??= 'release'
          observation.status = 'failed'
        }
      } catch (error) {
        observation.failure_stage ??= 'release'
        observation.failure_class ??= error?.name ?? 'Error'
        observation.status = 'failed'
      }
      observation.worker_pid_observed = Number.isSafeInteger(host.child?.pid)
    }
    observation.host_rss_after_bytes = process.memoryUsage().rss
    observations.push(observation)
  }
}

const attempted = observations.length
const succeeded = observations.filter(item => item.status === 'succeeded').length
const failed = attempted - succeeded
process.stdout.write(JSON.stringify({ platform: process.platform, node: process.version,
  planned, attempted, succeeded, failed, not_run: planned - attempted, setup_error: setupError, observations }) + '\n')
if (setupError || failed > 0) process.exitCode = 1
