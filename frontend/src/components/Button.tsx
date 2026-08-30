import type { ButtonHTMLAttributes } from 'react'

export type ButtonVariant = 'primary' | 'secondary' | 'destructive'
export type ControlDensity = 'default' | 'compact'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  density?: ControlDensity
}

export function Button({
  variant = 'primary',
  density = 'default',
  className,
  type = 'button',
  ...props
}: ButtonProps) {
  const classes = ['button', `button--${variant}`, `control--${density}`, className]
    .filter(Boolean)
    .join(' ')

  return <button className={classes} type={type} {...props} />
}
