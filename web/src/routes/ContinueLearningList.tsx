import LibraryListScreen from '../components/LibraryListScreen'
import { allContinueLearning } from '../lib/localLibrary'

/** Continue Learning (See All), screen 6 (FR-3.2). */
export function Component() {
  return (
    <LibraryListScreen
      variant="continue"
      title="Continue Learning"
      defaultSort="latest_opened"
      load={allContinueLearning}
      emptyMessage="Packages you start will appear here."
    />
  )
}
