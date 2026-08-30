import { cloneElement, type ReactElement } from 'react'
import type { ControlDensity } from './Button'

interface AccessibleControlProps {
  'aria-describedby'?: string
  'aria-invalid'?: boolean
  className?: string
  disabled?: boolean
  id?: string
}

export interface FormFieldProps {
  id: string
  label: string
  children: ReactElement<AccessibleControlProps>
  density?: ControlDensity
  description?: string
  error?: string
}

export function FormField({
  id,
  label,
  children,
  density = 'default',
  description,
  error,
}: FormFieldProps) {
  const descriptionId = description ? `${id}-description` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [children.props['aria-describedby'], descriptionId, errorId]
    .filter(Boolean)
    .join(' ') || undefined
  const controlClasses = [
    'form-control',
    `control--${density}`,
    children.props.className,
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div className="form-field">
      <label className="form-label" htmlFor={id}>
        {label}
      </label>
      {description ? (
        <span className="form-description" id={descriptionId}>
          {description}
        </span>
      ) : null}
      {cloneElement(children, {
        id,
        className: controlClasses,
        'aria-invalid': error ? true : children.props['aria-invalid'],
        'aria-describedby': describedBy,
      })}
      {error ? (
        <span className="form-error" id={errorId}>
          {error}
        </span>
      ) : null}
    </div>
  )
}
