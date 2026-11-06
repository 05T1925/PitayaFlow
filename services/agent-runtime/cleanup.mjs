/**
 * One attempt owns its DSH process and private home. A rejected close leaves
 * process exit unconfirmed, so the home remains available for diagnosis.
 */
export function createAttemptCleanup({ home, close, removeHome }) {
  let exitConfirmed = false
  let homeRemoved = false
  return async function cleanup() {
    if (!exitConfirmed) {
      try { await close(); exitConfirmed = true }
      catch (error) {
        return { exit_confirmed: false, home_removed: false, retained_home: home,
          error: { name: error?.name ?? 'Error', message: String(error?.message ?? error) } }
      }
    }
    if (!homeRemoved) {
      try { removeHome(home); homeRemoved = true }
      catch (error) {
        return { exit_confirmed: true, home_removed: false, retained_home: home,
          error: { name: error?.name ?? 'Error', message: String(error?.message ?? error) } }
      }
    }
    return { exit_confirmed: true, home_removed: true }
  }
}

/** Keep the work failure separate from cleanup failure for one attempt. */
export function attemptFailure(primaryError, cleanup) {
  return { primary_error: primaryError && { name: primaryError.name ?? 'Error', message: String(primaryError.message ?? primaryError) }, cleanup }
}
