import LibraryListScreen from '../components/LibraryListScreen'
import { allDownloads } from '../lib/localLibrary'

/** Recent Downloads (See All), screen 7 (FR-3.2, FR-3.3). */
export function Component() {
  return (
    <LibraryListScreen
      variant="download"
      title="Recent Downloads"
      defaultSort="latest_downloaded"
      load={allDownloads}
      emptyMessage="No downloads yet. Explore packages to download one."
    />
  )
}
