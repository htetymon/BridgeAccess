import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { FileTextIcon, ImageIcon, InfoIcon, LinkIcon, PlayIcon, UploadIcon, XIcon } from '../components/Icons'
import PageHeader from '../components/PageHeader'
import { createPackage } from '../lib/api/contributions'
import type { ResourceType } from '../lib/api/packages'
import {
  clearDraft,
  contentCounter,
  describeCreateError,
  EMPTY_DRAFT,
  fieldLabels,
  imageProblem,
  isEmptyDraft,
  isYouTubeLink,
  LIMITS,
  loadDraft,
  normalizeLink,
  saveDraft,
  toNewPackage,
  validateDraft,
  validateField,
  type Draft,
  type DraftField,
  type FieldErrors,
  type TextField,
} from '../lib/contribution'
import { fileKey, ImageError, prepareImage, type PreparedImage } from '../lib/images'
import { formatBytes } from '../lib/format'
import { useOnline } from '../lib/useOnline'

/** A chosen image; sourceKey identifies the picked file, to ignore picking it twice. */
type Picked = PreparedImage & { key: string; sourceKey: string; alt: string }

/** Field order on screen, so the first problem gets focus. */
const FIELD_ORDER: DraftField[] = ['originalTitle', 'author', 'content', 'images', 'resourceLink']

const TEXT_FIELDS: TextField[] = ['originalTitle', 'author', 'content', 'resourceLink']

/**
 * Create a Package, text and video (screens 10 and 11, FR-5.2 to FR-5.7, FR-5.9).
 *
 * - The text fields are saved on the device as a draft while typing, so going offline,
 *   leaving, or a failed upload never loses them. Offline, Create is disabled with a reason.
 * - Images are compressed on the device until they fit the 500 KB cap (FR-5.6).
 * - Inline validation (FR-5.5): each field is checked when left, then live while it is
 *   corrected; Content is always checked live against 10,000 characters. A video link must
 *   be a single YouTube video. Bare links get https:// added. Server errors map back to the
 *   same fields, and a repeat of this device's own earlier submit counts as success.
 */
export function Component() {
  const navigate = useNavigate()
  const online = useOnline()
  const [draft, setDraft] = useState<Draft>(loadDraft)
  const [restored] = useState(() => !isEmptyDraft(draft))
  const [showRestored, setShowRestored] = useState(restored)
  const [images, setImages] = useState<Picked[]>([])
  const [preparing, setPreparing] = useState(false)
  const [preparingNote, setPreparingNote] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})
  const [touched, setTouched] = useState<ReadonlySet<TextField>>(new Set())
  const [formError, setFormError] = useState<{ message: string; existingPackageId?: string } | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [showVisualsHelp, setShowVisualsHelp] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const errorSummary = useRef<HTMLDivElement>(null)
  const offlineNote = useRef<HTMLParagraphElement>(null)
  const ids = useIds()
  const labels = fieldLabels(draft.resourceType)
  const counter = contentCounter(draft.content.length)
  // A YouTube link under Text Resource is almost always a wrong type choice.
  const looksLikeVideo = draft.resourceType === 'text' && isYouTubeLink(draft.resourceLink)

  // Keep the draft on the device (debounced: typing should not write on every key).
  useEffect(() => {
    const t = setTimeout(() => saveDraft(draft), 400)
    return () => clearTimeout(t)
  }, [draft])

  // Free image previews when they are removed or the screen closes.
  const previews = useRef(images)
  useEffect(() => {
    previews.current = images
  })
  useEffect(() => () => previews.current.forEach((i) => URL.revokeObjectURL(i.previewUrl)), [])

  /**
   * Inline validation (FR-5.5): a field is checked when the contributor leaves it, and from
   * then on as they type, so a message updates or disappears the moment the input is fixed.
   * Nobody is told "required" while typing for the first time. Content is always checked
   * live, so going over the limit (often by pasting) shows at once.
   */
  function update(field: TextField, value: string) {
    const next = { ...draft, [field]: value }
    setDraft(next)
    if (field === 'content' || touched.has(field) || errors[field]) {
      setErrors((e) => ({ ...e, [field]: validateField(field, next) }))
    }
  }

  function blur(field: TextField) {
    let next = draft
    if (field === 'resourceLink') {
      const tidy = normalizeLink(draft.resourceLink)
      if (tidy !== draft.resourceLink) {
        next = { ...draft, resourceLink: tidy }
        setDraft(next)
      }
    }
    setTouched((t) => new Set(t).add(field))
    // An empty field the contributor only passed through is not an error yet.
    if (!next[field].trim() && !touched.has(field)) return
    setErrors((e) => ({ ...e, [field]: validateField(field, next) }))
  }

  function setType(type: ResourceType) {
    const next = { ...draft, resourceType: type }
    setDraft(next)
    // Messages and the link rule depend on the type (Video Title; video must be YouTube).
    setErrors((e) => {
      const updated = { ...e }
      for (const field of TEXT_FIELDS) {
        if (touched.has(field) || e[field]) updated[field] = validateField(field, next)
      }
      return updated
    })
  }

  /**
   * FR-5.4, FR-5.6: up to 3 images, each checked by content and compressed on the device
   * until it is 500 KB or less. One at a time, so a low-end phone holds one decoded photo.
   * A file that cannot be used is skipped with a message; the others are still added.
   */
  async function addImages(files: FileList | null) {
    if (!files || files.length === 0) return
    const picked = Array.from(files)
    if (fileInput.current) fileInput.current.value = '' // allow picking the same file again later
    const known = new Set(images.map((i) => i.sourceKey))
    const fresh = picked.filter((f) => !known.has(fileKey(f)))
    const room = Math.max(0, LIMITS.maxImages - images.length)
    const chosen = fresh.slice(0, room)
    const problems: string[] = []
    if (fresh.length < picked.length) problems.push('That image is already added.')
    if (fresh.length > room) {
      problems.push(
        room === 0
          ? `You can add up to ${LIMITS.maxImages} images.`
          : `Only ${room} more ${room === 1 ? 'image fits' : 'images fit'}; you can add up to ${LIMITS.maxImages}.`,
      )
    }
    setPreparing(true)
    const added: Picked[] = []
    for (const [n, file] of chosen.entries()) {
      setPreparingNote(chosen.length > 1 ? `Preparing image ${n + 1} of ${chosen.length}…` : 'Preparing image…')
      try {
        const prepared = await prepareImage(file)
        const issue = imageProblem(prepared.blob)
        if (issue) {
          URL.revokeObjectURL(prepared.previewUrl)
          problems.push(`${file.name}: ${issue}`)
          continue
        }
        added.push({ ...prepared, key: `${Date.now()}-${n}`, sourceKey: fileKey(file), alt: '' })
      } catch (e) {
        problems.push(`${file.name}: ${e instanceof ImageError ? e.message : 'This image could not be added.'}`)
      }
    }
    setPreparing(false)
    setPreparingNote('')
    setImages((current) => [...current, ...added].slice(0, LIMITS.maxImages))
    setErrors((e) => ({ ...e, images: problems.length > 0 ? problems.join(' ') : undefined }))
  }

  function removeImage(key: string) {
    setImages((current) => {
      const gone = current.find((i) => i.key === key)
      if (gone) URL.revokeObjectURL(gone.previewUrl)
      return current.filter((i) => i.key !== key)
    })
    setErrors((e) => ({ ...e, images: undefined }))
  }

  function showErrors(next: FieldErrors) {
    setErrors(next)
    const first = FIELD_ORDER.find((f) => next[f])
    if (first) document.getElementById(ids[first])?.focus()
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (submitting || preparing) return
    if (!online) {
      offlineNote.current?.focus()
      return
    }
    setFormError(null)
    setTouched(new Set(TEXT_FIELDS))
    // Tidy the link even if the field was never left (Create tapped straight from it).
    const ready = { ...draft, resourceLink: normalizeLink(draft.resourceLink) }
    if (ready.resourceLink !== draft.resourceLink) setDraft(ready)
    const problems = validateDraft(ready)
    if (Object.values(problems).some(Boolean)) {
      showErrors(problems)
      return
    }
    setSubmitting(true)
    try {
      const created = await createPackage(
        toNewPackage(ready, images.map((i) => i.alt)),
        images.map((i) => new File([i.blob], i.name, { type: i.blob.type })),
      )
      done(created.id)
    } catch (e) {
      const outcome = describeCreateError(e)
      if (outcome.kind === 'created') {
        done(outcome.packageId)
        return
      }
      setSubmitting(false)
      setFormError({ message: outcome.message, existingPackageId: outcome.existingPackageId })
      if (Object.keys(outcome.fieldErrors).length > 0) showErrors(outcome.fieldErrors)
      else errorSummary.current?.focus()
    }
  }

  function done(packageId: string) {
    clearDraft()
    navigate('/contribute', { replace: true, state: { created: packageId } })
  }

  function discardDraft() {
    setDraft(EMPTY_DRAFT)
    setErrors({})
    setTouched(new Set())
    setShowRestored(false)
    clearDraft()
  }

  return (
    <main className="screen">
      <title>Create a Package · BridgeAccess</title>
      <PageHeader
        title="Create a Package"
        subtitle="Share a learning package with the community."
        back={{ fallback: '/contribute' }}
      />

      {showRestored && (
        <div className="notice" role="status">
          <InfoIcon size={20} />
          <p>We kept your unfinished package. Images are not kept; add them again.</p>
          <button type="button" className="notice__action" onClick={discardDraft}>
            Start over
          </button>
        </div>
      )}

      {formError && (
        <div className="notice notice--error" role="alert" tabIndex={-1} ref={errorSummary}>
          <p>
            {formError.message}{' '}
            {formError.existingPackageId && (
              <Link to={`/packages/${formError.existingPackageId}`}>Open the existing package</Link>
            )}
          </p>
        </div>
      )}

      <form className="form" onSubmit={submit} noValidate>
        <fieldset className="field">
          <legend className="field__label">
            Resource Type <Required />
          </legend>
          <div className="type-options">
            <TypeOption type="text" current={draft.resourceType} onChange={setType} icon={<FileTextIcon size={20} />}>
              Text Resource
            </TypeOption>
            <TypeOption type="video" current={draft.resourceType} onChange={setType} icon={<PlayIcon size={20} />}>
              Video Resource
            </TypeOption>
          </div>
        </fieldset>

        <InputField
          id={ids.originalTitle}
          label={labels.title}
          required
          value={draft.originalTitle}
          onChange={(v) => update('originalTitle', v)}
          onBlur={() => blur('originalTitle')}
          placeholder={draft.resourceType === 'video' ? 'Enter the title of the video' : 'Enter the original title'}
          maxLength={LIMITS.titleChars}
          error={errors.originalTitle}
        />

        <InputField
          id={ids.author}
          label={labels.author}
          required
          value={draft.author}
          onChange={(v) => update('author', v)}
          onBlur={() => blur('author')}
          placeholder={draft.resourceType === 'video' ? 'Enter the channel name' : 'Enter the author'}
          maxLength={LIMITS.authorChars}
          error={errors.author}
          autoComplete="off"
        />

        <div className="field">
          <label className="field__label" htmlFor={ids.content}>
            {labels.content} <span className="field__optional">(Optional)</span>
          </label>
          <textarea
            id={ids.content}
            className="input input--area"
            rows={5}
            value={draft.content}
            onChange={(e) => update('content', e.target.value)}
            placeholder={
              draft.resourceType === 'video'
                ? 'Paste the video transcript here'
                : 'Paste the article or resource content here'
            }
            aria-invalid={Boolean(errors.content)}
            aria-describedby={`${ids.content}-hint ${ids.content}-count${errors.content ? ` ${ids.content}-error` : ''}`}
          />
          <div className="field__foot">
            <span id={`${ids.content}-hint`} className="field__hint">
              {draft.resourceType === 'video'
                ? "Without a transcript we try the video page; if that doesn't work you'll be asked to paste one."
                : 'Leave empty and we read the link. Paste the text if the page needs a login.'}
            </span>
            {/* Not cut off at the limit: a long paste stays visible, turns red and says how much to remove. */}
            <span id={`${ids.content}-count`} className={`field__count field__count--${counter.state}`}>
              {counter.text}
            </span>
          </div>
          {/* Screen readers hear the count only near and over the limit, not on every key. */}
          <span className="visually-hidden" aria-live="polite">
            {counter.announce}
          </span>
          <FieldError id={`${ids.content}-error`} message={errors.content} />
        </div>

        <div className="field">
          <div className="field__label-row">
            <span className="field__label" id={`${ids.images}-label`}>
              Add Visuals <span className="field__optional">(Optional)</span>
            </span>
            <button
              type="button"
              className="icon-button icon-button--small"
              aria-expanded={showVisualsHelp}
              aria-controls={`${ids.images}-help`}
              aria-label="About visuals"
              onClick={() => setShowVisualsHelp((s) => !s)}
            >
              <InfoIcon size={18} />
            </button>
          </div>
          {showVisualsHelp && (
            <p id={`${ids.images}-help`} className="field__hint field__help">
              Images make this a "With Visual" package and add a Diagram step. Large photos are made smaller on your
              phone before uploading, to save data.
            </p>
          )}

          <div className="dropzone">
            <div className="dropzone__art" aria-hidden="true">
              <ImageIcon size={32} />
            </div>
            <div className="dropzone__body">
              <p>
                {draft.resourceType === 'video'
                  ? 'Add supporting images (e.g., key frame, diagram, summary)'
                  : 'Add images (e.g., diagram, chart, illustration)'}
              </p>
              <button
                id={ids.images}
                type="button"
                className="button button--outline button--small"
                onClick={() => fileInput.current?.click()}
                disabled={images.length >= LIMITS.maxImages || preparing}
                aria-describedby={`${ids.images}-rules${errors.images ? ` ${ids.images}-error` : ''}`}
              >
                <UploadIcon size={18} /> {preparing ? 'Preparing…' : 'Choose Images'}
              </button>
              <input
                ref={fileInput}
                type="file"
                accept="image/png,image/jpeg,image/webp"
                multiple
                hidden
                onChange={(e) => addImages(e.target.files)}
              />
            </div>
          </div>
          <p id={`${ids.images}-rules`} className="field__hint">
            Maximum {LIMITS.maxImages} images • Up to 500 KB per image (PNG, JPG, WebP)
            {images.length > 0 && ` • ${images.length} of ${LIMITS.maxImages} added`}
          </p>
          <p className="field__hint" role="status" aria-live="polite">
            {preparingNote}
          </p>
          <FieldError id={`${ids.images}-error`} message={errors.images} />

          {images.length > 0 && (
            <ul className="thumbs" aria-label="Chosen images">
              {images.map((image, i) => (
                <li key={image.key} className="thumb">
                  <img src={image.previewUrl} alt="" className="thumb__img" />
                  <div className="thumb__body">
                    <p className="thumb__size">
                      {formatBytes(image.blob.size)}
                      {image.reencoded && image.originalBytes > image.blob.size && (
                        <span> · made smaller from {formatBytes(image.originalBytes)}</span>
                      )}
                    </p>
                    <input
                    className="input input--small"
                    value={image.alt}
                    onChange={(e) =>
                      setImages((all) => all.map((x) => (x.key === image.key ? { ...x, alt: e.target.value } : x)))
                    }
                    placeholder="Describe the image (optional)"
                    aria-label={`Description of image ${i + 1}`}
                    maxLength={300}
                  />
                  </div>
                  <button
                    type="button"
                    className="icon-button icon-button--small thumb__remove"
                    onClick={() => removeImage(image.key)}
                    aria-label={`Remove image ${i + 1}`}
                  >
                    <XIcon size={18} />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="field">
          <label className="field__label" htmlFor={ids.resourceLink}>
            Resource Link <Required />
          </label>
          <div className="input-with-icon">
            <LinkIcon size={20} />
            <input
              id={ids.resourceLink}
              className="input"
              type="url"
              inputMode="url"
              autoComplete="url"
              autoCapitalize="none"
              spellCheck={false}
              value={draft.resourceLink}
              onChange={(e) => update('resourceLink', e.target.value)}
              onBlur={() => blur('resourceLink')}
              placeholder={labels.link}
              required
              aria-invalid={Boolean(errors.resourceLink)}
              aria-describedby={
                [errors.resourceLink && `${ids.resourceLink}-error`, looksLikeVideo && `${ids.resourceLink}-suggest`]
                  .filter(Boolean)
                  .join(' ') || undefined
              }
            />
          </div>
          <FieldError id={`${ids.resourceLink}-error`} message={errors.resourceLink} />
          {looksLikeVideo && (
            <p id={`${ids.resourceLink}-suggest`} className="field__suggest">
              <span>This looks like a YouTube video.</span>
              <button type="button" className="field__suggest-action" onClick={() => setType('video')}>
                Switch to Video Resource
              </button>
            </p>
          )}
        </div>

        {!online && (
          <p id={`${ids.resourceType}-offline`} className="notice" role="status" tabIndex={-1} ref={offlineNote}>
            <InfoIcon size={20} />
            <span>You're offline. Creating a package needs a connection. Your draft is saved on this device.</span>
          </p>
        )}

        <button
          type="submit"
          className="button button--primary button--block form__submit"
          // aria-disabled rather than disabled when offline: the button stays focusable, so
          // keyboard and screen-reader users reach it and hear why it does not work.
          disabled={submitting || preparing}
          aria-disabled={!online}
          aria-describedby={online ? undefined : `${ids.resourceType}-offline`}
        >
          {submitting ? 'Uploading…' : online ? 'Create Package' : 'Create Package (needs a connection)'}
        </button>
      </form>
    </main>
  )
}

function useIds(): Record<DraftField, string> {
  const base = useId()
  return {
    resourceType: `${base}-type`,
    originalTitle: `${base}-title`,
    author: `${base}-author`,
    content: `${base}-content`,
    images: `${base}-images`,
    resourceLink: `${base}-link`,
  }
}

function Required() {
  return (
    <span className="field__required" aria-hidden="true">
      *
    </span>
  )
}

function FieldError({ id, message }: { id: string; message?: string }) {
  if (!message) return null
  return (
    <p id={id} className="field__error">
      {message}
    </p>
  )
}

function TypeOption({
  type,
  current,
  onChange,
  icon,
  children,
}: {
  type: ResourceType
  current: ResourceType
  onChange: (t: ResourceType) => void
  icon: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <label className={`type-option${current === type ? ' type-option--selected' : ''}`}>
      <input
        type="radio"
        name="resourceType"
        value={type}
        checked={current === type}
        onChange={() => onChange(type)}
        className="type-option__radio"
      />
      {icon}
      <span>{children}</span>
    </label>
  )
}

function InputField(props: {
  id: string
  label: string
  value: string
  onChange: (v: string) => void
  placeholder: string
  maxLength: number
  required?: boolean
  error?: string
  autoComplete?: string
  onBlur: () => void
}) {
  const errorId = `${props.id}-error`
  return (
    <div className="field">
      <label className="field__label" htmlFor={props.id}>
        {props.label} {props.required && <Required />}
      </label>
      <input
        id={props.id}
        className="input"
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
        onBlur={props.onBlur}
        placeholder={props.placeholder}
        maxLength={props.maxLength}
        required={props.required}
        autoComplete={props.autoComplete}
        aria-invalid={Boolean(props.error)}
        aria-describedby={props.error ? errorId : undefined}
      />
      <FieldError id={errorId} message={props.error} />
    </div>
  )
}
