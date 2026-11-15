import { useEffect, useRef, type ImgHTMLAttributes } from 'react'

/**
 * An image stored in IndexedDB. The object URL is set on the element and released when the
 * blob changes or the image unmounts, so no URL leaks and no extra render is needed.
 */
export default function BlobImage({ blob, ...props }: { blob: Blob } & Omit<ImgHTMLAttributes<HTMLImageElement>, 'src'>) {
  const ref = useRef<HTMLImageElement>(null)
  useEffect(() => {
    if (!ref.current) return
    const url = URL.createObjectURL(blob)
    ref.current.src = url
    return () => URL.revokeObjectURL(url)
  }, [blob])
  return <img ref={ref} decoding="async" {...props} />
}
