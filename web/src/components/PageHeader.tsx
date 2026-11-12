import { useLocation, useNavigate } from 'react-router'
import { ChevronLeftIcon } from './Icons'

type Props = {
  title: string
  subtitle?: string
  /** Show a back button. `fallback` is used when the screen was opened directly (no history). */
  back?: { fallback: string }
}

export default function PageHeader({ title, subtitle, back }: Props) {
  const navigate = useNavigate()
  const location = useLocation()

  function goBack() {
    // 'default' is the key of the first entry in this tab, e.g. a deep link or refresh.
    if (location.key === 'default') navigate(back!.fallback, { replace: true })
    else navigate(-1)
  }

  return (
    <header className="page-header">
      {back && (
        <button type="button" className="icon-button" onClick={goBack} aria-label="Back">
          <ChevronLeftIcon />
        </button>
      )}
      <h1>{title}</h1>
      {subtitle && <p className="page-header__subtitle">{subtitle}</p>}
    </header>
  )
}
